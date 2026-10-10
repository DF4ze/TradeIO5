package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveTrendConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveUiMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveUserSettings;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveTrendConfigRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveUserSettingsRepository;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetTemplate;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetTemplateRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
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
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLivePresetTemplateRepository templateRepository;
    private final RainbowLiveTrendConfigRepository trendConfigRepository;
    private final RainbowLiveUserSettingsRepository settingsRepository;
    private final DomainClock clock;

    /** Création : actif, nom et capital initial sont fixés ici (non modifiables ensuite). */
    public record CreateRequest(String assetSymbol, String name, boolean enabled, int analysisWindowMonths,
                                double initialCapitalUsdc, RainbowAtrTuning tuning, RainbowAtrGlobals globals,
                                String mode, TrendConfigDto trendConfig) {
        /** Preset FIXED (tuning/globals propres). */
        public CreateRequest(String assetSymbol, String name, boolean enabled, int analysisWindowMonths,
                             double initialCapitalUsdc, RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
            this(assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc, tuning, globals, null, null);
        }
    }

    /** Édition : identité (user, actif), wallet et historique conservés. */
    public record UpdateRequest(String name, boolean enabled, int analysisWindowMonths,
                                RainbowAtrTuning tuning, RainbowAtrGlobals globals, TrendConfigDto trendConfig) {
        public UpdateRequest(String name, boolean enabled, int analysisWindowMonths,
                             RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
            this(name, enabled, analysisWindowMonths, tuning, globals, null);
        }
    }

    @Transactional
    public RainbowLivePreset create(User user, CreateRequest req) {
        if (req.name() != null && req.name().startsWith(RainbowLiveDefaultPresets.SYSTEM_PREFIX)) {
            throw new IllegalArgumentException("Le préfixe « " + RainbowLiveDefaultPresets.SYSTEM_PREFIX
                    + "» est réservé aux presets système");
        }
        return doCreate(user, req, false);
    }

    private RainbowLivePreset doCreate(User user, CreateRequest req, boolean system) {
        requireAllowedAsset(req.assetSymbol());
        boolean trend = RainbowLiveMode.TREND_MIX.name().equals(req.mode());
        if (req.mode() != null && !trend && !RainbowLiveMode.FIXED.name().equals(req.mode())) {
            throw new IllegalArgumentException("mode invalide : " + req.mode());
        }
        RainbowLiveTrendConfigs.Resolved resolved = trend
                ? RainbowLiveTrendConfigs.normalize(req.assetSymbol(), req.trendConfig()) : null;
        RainbowAtrTuning tuning = trend ? resolved.bear().tuning() : req.tuning();
        RainbowAtrGlobals globals = trend ? resolved.bear().globals() : req.globals();
        requireValid(req.name(), req.analysisWindowMonths(), tuning, globals);
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
                .system(system)
                .analysisWindowMonths(req.analysisWindowMonths())
                .initialCapitalUsdc(req.initialCapitalUsdc())
                .createdAt(now)
                .updatedAt(now)
                .config(RainbowAtrConfig.of(tuning, globals))
                .mode(trend ? RainbowLiveMode.TREND_MIX : null)
                .build());
        if (trend) {
            saveTrendConfig(preset, resolved);
        }
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
        requireNotSystem(preset);
        if (req.name() != null && req.name().startsWith(RainbowLiveDefaultPresets.SYSTEM_PREFIX)) {
            throw new IllegalArgumentException("Le préfixe « " + RainbowLiveDefaultPresets.SYSTEM_PREFIX
                    + "» est réservé aux presets système");
        }
        RainbowLiveTrendConfigs.Resolved resolved = null;
        RainbowAtrTuning tuning = req.tuning();
        RainbowAtrGlobals globals = req.globals();
        if (preset.isTrendMix()) {
            RainbowLiveTrendConfigs.Resolved current = RainbowLiveTrendConfigs.resolve(
                    trendConfigRepository.findByPreset(preset).orElse(null), preset.getAssetSymbol());
            resolved = req.trendConfig() == null ? current
                    : RainbowLiveTrendConfigs.normalize(preset.getAssetSymbol(), req.trendConfig());
            tuning = resolved.bear().tuning();
            globals = resolved.bear().globals();
        }
        requireValid(req.name(), req.analysisWindowMonths(), tuning, globals);
        requireNameFree(user, preset.getAssetSymbol(), req.name(), preset.getId());

        preset.setName(req.name());
        preset.setEnabled(req.enabled());
        preset.setAnalysisWindowMonths(req.analysisWindowMonths());
        preset.setConfig(RainbowAtrConfig.of(tuning, globals));
        preset.setUpdatedAt(clock.now());
        RainbowLivePreset saved = presetRepository.save(preset);
        if (resolved != null) {
            saveTrendConfig(saved, resolved);
        }
        log.info("Preset bench modifié id={} user={} actif={} nom='{}' enabled={}",
                saved.getId(), user.getId(), saved.getAssetSymbol(), saved.getName(), saved.isEnabled());
        log.debug("Preset bench id={} config={}", saved.getId(), saved.getConfig());
        return saved;
    }

    /** Supprime le preset ; wallet mock et runs partent en cascade (FK). */
    @Transactional
    public void delete(User user, Long presetId) {
        RainbowLivePreset preset = owned(user, presetId);
        requireNotSystem(preset);
        if (bindingRepository.existsByPreset(preset)) {
            throw new IllegalArgumentException("Preset live d'un binding : le délier ou le remplacer avant suppression");
        }
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

    /** Active / désactive un preset (seul champ modifiable d'un preset système). */
    @Transactional
    public RainbowLivePreset setEnabled(User user, Long presetId, boolean enabled) {
        RainbowLivePreset preset = owned(user, presetId);
        preset.setEnabled(enabled);
        preset.setUpdatedAt(clock.now());
        RainbowLivePreset saved = presetRepository.save(preset);
        log.info("Preset bench id={} user={} enabled={}", saved.getId(), user.getId(), enabled);
        return saved;
    }

    /**
     * Première utilisation d'un user : copie de chaque template système dont il n'a pas encore la copie (un à un,
     * clé : actif + nom préfixé). Copies créées inactives, non modifiables, non supprimables (wallet mock et runs
     * propres). Idempotent : une copie existante n'est jamais recréée ni modifiée.
     *
     * @return les presets créés (vide si rien à faire)
     */
    @Transactional
    public List<RainbowLivePreset> ensureSystemPresets(User user) {
        List<RainbowLivePreset> created = new ArrayList<>();
        for (RainbowLivePresetTemplate template : templateRepository.findAllByOrderByAssetSymbolAscNameAsc()) {
            String name = RainbowLiveDefaultPresets.SYSTEM_PREFIX + template.getName();
            if (presetRepository.findByUserAndAssetSymbolAndName(user, template.getAssetSymbol(), name).isPresent()) {
                continue;
            }
            created.add(copyTemplate(user, template, name));
        }
        if (!created.isEmpty()) {
            log.info("Presets système copiés pour user={} : {} preset(s) créé(s)", user.getId(), created.size());
        }
        return created;
    }

    private RainbowLivePreset copyTemplate(User user, RainbowLivePresetTemplate t, String name) {
        CreateRequest req;
        if (t.getMode() == RainbowLiveMode.TREND_MIX) {
            req = new CreateRequest(t.getAssetSymbol(), name, false, t.getAnalysisWindowMonths(),
                    t.getInitialCapitalUsdc(), null, null, RainbowLiveMode.TREND_MIX.name(),
                    RainbowLiveTrendConfigs.fromJson(t.getTrendConfigJson()));
        } else {
            req = new CreateRequest(t.getAssetSymbol(), name, false, t.getAnalysisWindowMonths(),
                    t.getInitialCapitalUsdc(), t.getConfig().toTuning(), t.getConfig().toGlobals());
        }
        return doCreate(user, req, true);
    }

    /**
     * Preset {@code TREND_MIX} (jeux Bull/Bear de l'actif choisis par la Trend). La config stockée est celle du jeu
     * Bear de l'actif : seul {@code baseAmount} y est lu à l'exécution, le reste est un instantané informatif.
     */
    @Transactional
    public RainbowLivePreset createTrendMix(User user, String assetSymbol, String name, boolean enabled,
                                            int analysisWindowMonths, double initialCapitalUsdc) {
        return create(user, new CreateRequest(assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc,
                null, null, RainbowLiveMode.TREND_MIX.name(), null));
    }

    /** Réglages résolus d'un preset (défauts du code si aucune ligne). */
    @Transactional(readOnly = true)
    public RainbowLiveTrendConfigs.Resolved trendConfig(RainbowLivePreset preset) {
        return RainbowLiveTrendConfigs.resolve(trendConfigRepository.findByPreset(preset).orElse(null),
                preset.getAssetSymbol());
    }

    private void saveTrendConfig(RainbowLivePreset preset, RainbowLiveTrendConfigs.Resolved resolved) {
        RainbowLiveTrendConfig row = trendConfigRepository.findByPreset(preset)
                .orElseGet(() -> RainbowLiveTrendConfig.builder().preset(preset).build());
        RainbowLiveTrendConfigs.apply(row, resolved);
        trendConfigRepository.save(row);
    }

    /** Mode d'affichage de l'utilisateur ; {@code EXPERT} tant qu'il n'en a pas choisi. */
    @Transactional(readOnly = true)
    public RainbowLiveUiMode uiMode(User user) {
        return settingsRepository.findByUser(user).map(RainbowLiveUserSettings::getUiMode).orElse(RainbowLiveUiMode.EXPERT);
    }

    @Transactional
    public RainbowLiveUiMode setUiMode(User user, String mode) {
        RainbowLiveUiMode m;
        try {
            m = RainbowLiveUiMode.valueOf(mode);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Mode d'affichage invalide : " + mode);
        }
        RainbowLiveUserSettings row = settingsRepository.findByUser(user)
                .orElseGet(() -> RainbowLiveUserSettings.builder().user(user).build());
        row.setUiMode(m);
        settingsRepository.save(row);
        return m;
    }

    private RainbowLivePreset owned(User user, Long presetId) {
        return presetRepository.findById(presetId)
                .filter(p -> p.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RainbowLivePresetNotFoundException(presetId));
    }

    private static void requireNotSystem(RainbowLivePreset preset) {
        if (preset.isSystem()) {
            throw new RainbowLivePresetLockedException(preset.getId());
        }
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
