package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveUserSeed;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveUserSeedRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * CRUD des presets du bench grandeur nature (persistance seule, aucun calcul moteur, aucun appel exchange).
 * La suppression cascade (FK {@code ON DELETE CASCADE}) vers le wallet mock et les runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLivePresetService {

    private final RainbowLivePresetRepository presetRepository;
    private final RainbowLiveMockWalletRepository walletRepository;
    private final RainbowLiveUserSeedRepository seedRepository;
    private final DomainClock clock;

    /** Création : actif, nom et capital initial sont fixés ici (non modifiables ensuite). */
    public record CreateRequest(String assetSymbol, String name, boolean enabled, int analysisWindowMonths,
                                double initialCapitalUsdc, RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
    }

    /** Édition : identité (user, actif), wallet et historique conservés. */
    public record UpdateRequest(String name, boolean enabled, int analysisWindowMonths,
                                RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
    }

    @Transactional
    public RainbowLivePreset create(User user, CreateRequest req) {
        requireAllowedAsset(req.assetSymbol());
        requireValid(req.name(), req.analysisWindowMonths(), req.tuning(), req.globals());
        if (req.initialCapitalUsdc() <= 0) {
            throw new IllegalArgumentException("initialCapitalUsdc doit être > 0");
        }
        requireNameFree(user, req.assetSymbol(), req.name(), null);

        Instant now = clock.now();
        RainbowLivePreset preset = presetRepository.save(RainbowLivePreset.builder()
                .user(user)
                .assetSymbol(req.assetSymbol())
                .name(req.name())
                .enabled(req.enabled())
                .analysisWindowMonths(req.analysisWindowMonths())
                .initialCapitalUsdc(req.initialCapitalUsdc())
                .createdAt(now)
                .updatedAt(now)
                .config(RainbowAtrConfig.of(req.tuning(), req.globals()))
                .build());
        walletRepository.save(RainbowLiveMockWallet.builder()
                .preset(preset)
                .assetSymbol(preset.getAssetSymbol())
                .cashUsdc(preset.getInitialCapitalUsdc())
                .positionQuantity(0.0)
                .updatedAt(now)
                .build());
        log.info("Preset bench créé id={} user={} actif={} nom='{}' capital={} {}",
                preset.getId(), user.getId(), preset.getAssetSymbol(), preset.getName(),
                preset.getInitialCapitalUsdc(), RainbowLiveDefaultPresets.STABLECOIN);
        log.debug("Preset bench id={} config={}", preset.getId(), preset.getConfig());
        return preset;
    }

    /** Édite le preset ; n'écrit aucun run, ne touche pas au wallet mock. */
    @Transactional
    public RainbowLivePreset update(User user, Long presetId, UpdateRequest req) {
        RainbowLivePreset preset = owned(user, presetId);
        requireValid(req.name(), req.analysisWindowMonths(), req.tuning(), req.globals());
        requireNameFree(user, preset.getAssetSymbol(), req.name(), preset.getId());

        preset.setName(req.name());
        preset.setEnabled(req.enabled());
        preset.setAnalysisWindowMonths(req.analysisWindowMonths());
        preset.setConfig(RainbowAtrConfig.of(req.tuning(), req.globals()));
        preset.setUpdatedAt(clock.now());
        RainbowLivePreset saved = presetRepository.save(preset);
        log.info("Preset bench modifié id={} user={} actif={} nom='{}' enabled={}",
                saved.getId(), user.getId(), saved.getAssetSymbol(), saved.getName(), saved.isEnabled());
        log.debug("Preset bench id={} config={}", saved.getId(), saved.getConfig());
        return saved;
    }

    /** Supprime le preset ; wallet mock et runs partent en cascade (FK). */
    @Transactional
    public void delete(User user, Long presetId) {
        RainbowLivePreset preset = owned(user, presetId);
        presetRepository.delete(preset);
        presetRepository.flush();
        log.info("Preset bench supprimé id={} user={} actif={} nom='{}' (wallet mock + runs en cascade)",
                presetId, user.getId(), preset.getAssetSymbol(), preset.getName());
    }

    @Transactional(readOnly = true)
    public List<RainbowLivePreset> list(User user) {
        return presetRepository.findByUserOrderByAssetSymbolAscNameAsc(user);
    }

    @Transactional(readOnly = true)
    public List<RainbowLivePreset> list(User user, String assetSymbol) {
        return presetRepository.findByUserAndAssetSymbolOrderByNameAsc(user, assetSymbol);
    }

    /** Preset de l'utilisateur ; inexistant ou d'un autre utilisateur ⇒ {@link RainbowLivePresetNotFoundException}. */
    @Transactional(readOnly = true)
    public RainbowLivePreset get(User user, Long presetId) {
        return owned(user, presetId);
    }

    /**
     * Seed lazy, une seule fois par utilisateur (marqueur {@link RainbowLiveUserSeed}) : marqueur présent ⇒ no-op ;
     * absent ⇒ crée le preset par défaut (jeu global du bench) de chaque actif autorisé sans preset, puis pose le
     * marqueur. Supprimer ensuite tous les presets d'un actif = ne plus le suivre (jamais re-créé).
     *
     * @return les presets créés (vide si rien à faire)
     */
    @Transactional
    public List<RainbowLivePreset> ensureDefaultPresets(User user) {
        if (seedRepository.existsByUser(user)) {
            return List.of();
        }
        List<RainbowLivePreset> created = RainbowLiveDefaultPresets.ASSETS.stream()
                .filter(asset -> !presetRepository.existsByUserAndAssetSymbol(user, asset))
                .map(asset -> {
                    RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor(asset);
                    return create(user, new CreateRequest(asset, RainbowLiveDefaultPresets.DEFAULT_NAME, true,
                            RainbowLiveDefaultPresets.DEFAULT_ANALYSIS_WINDOW_MONTHS,
                            RainbowLiveDefaultPresets.DEFAULT_INITIAL_CAPITAL_USDC, c.toTuning(), c.toGlobals()));
                })
                .toList();
        seedRepository.save(RainbowLiveUserSeed.builder().user(user).seededAt(clock.now()).build());
        log.info("Presets bench par défaut semés pour user={} : {} preset(s) créé(s), marqueur posé",
                user.getId(), created.size());
        return created;
    }

    private RainbowLivePreset owned(User user, Long presetId) {
        return presetRepository.findById(presetId)
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RainbowLivePresetNotFoundException(presetId));
    }

    private void requireNameFree(User user, String asset, String name, Long selfId) {
        presetRepository.findByUserAndAssetSymbolAndName(user, asset, name)
                .filter(p -> !p.getId().equals(selfId))
                .ifPresent(p -> {
                    throw new RainbowLivePresetConflictException(name, asset);
                });
    }

    private static void requireAllowedAsset(String asset) {
        if (asset == null || !RainbowLiveDefaultPresets.ASSETS.contains(asset)) {
            throw new IllegalArgumentException("Actif non autorisé : " + asset
                    + " (autorisés : " + RainbowLiveDefaultPresets.ASSETS + ")");
        }
    }

    private static void requireValid(String name, int windowMonths, RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name est requis");
        }
        if (windowMonths < 1) {
            throw new IllegalArgumentException("analysisWindowMonths doit être ≥ 1");
        }
        if (tuning == null || !tuning.isValid()) {
            throw new IllegalArgumentException("Tuning invalide : " + tuning);
        }
        if (globals == null || globals.baseAmount() <= 0) {
            throw new IllegalArgumentException("baseAmount doit être > 0");
        }
    }
}
