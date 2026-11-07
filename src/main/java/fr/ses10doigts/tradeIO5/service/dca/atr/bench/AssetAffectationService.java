package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveEngineStateRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Affectations (utilisateur, actif) : persistance et règles de cohérence, aucun calcul moteur. Première utilisation d'un
 * utilisateur = une Affectation par actif sur les presets système de base (inactive). Changer les presets d'une
 * Affectation purge son état moteur : la prochaine passe repart des nouveaux presets (rejeu de la fenêtre), sans reprendre
 * les armements / cooldown de l'ancienne config.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAffectationService {

    private final AssetAffectationRepository repository;
    private final TrendPresetRepository trendRepository;
    private final RainbowPresetRepository rainbowRepository;
    private final RainbowLiveEngineStateRepository stateRepository;
    private final RainbowLivePresetEventService eventService;
    private final DomainClock clock;

    /** Une Affectation par actif sur les presets système de base, pour chaque actif où l'utilisateur n'en a pas. */
    @Transactional
    public List<AssetAffectation> ensureAffectations(User user) {
        List<AssetAffectation> created = new ArrayList<>();
        Instant now = clock.now();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            if (repository.findByUserAndAssetSymbol(user, asset).isPresent()) {
                continue;
            }
            TrendPreset trend = trendRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.TREND).orElse(null);
            RainbowPreset up = rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BULL).orElse(null);
            RainbowPreset down = rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BEAR).orElse(null);
            if (trend == null || up == null || down == null) {
                log.warn("Presets de base absents pour {} : Affectation de user={} non créée", asset, user.getId());
                continue;
            }
            created.add(repository.save(AssetAffectation.builder().user(user).assetSymbol(asset).trendPreset(trend)
                    .rainbowUp(up).rainbowDown(down).enabled(false).createdAt(now).updatedAt(now).build()));
        }
        if (!created.isEmpty()) {
            log.info("Affectations créées pour user={} : {}", user.getId(), created.size());
        }
        return created;
    }

    /** Affectation de l'utilisateur sur l'actif, créée sur les presets de base si absente. */
    @Transactional
    public AssetAffectation forAsset(User user, String assetSymbol) {
        ensureAffectations(user);
        return repository.findByUserAndAssetSymbol(user, assetSymbol)
                .orElseThrow(() -> new IllegalStateException("Aucune Affectation possible pour " + assetSymbol + " (presets de base absents)"));
    }

    @Transactional(readOnly = true)
    public List<AssetAffectation> list(User user) {
        return repository.findByUser(user).stream().sorted(java.util.Comparator
                .comparingInt(a -> RainbowLiveDefaultPresets.ASSETS.indexOf(a.getAssetSymbol()))).toList();
    }

    /** Affectation de l'utilisateur ; inexistante ou d'un autre utilisateur ⇒ {@link AssetAffectationNotFoundException}. */
    @Transactional(readOnly = true)
    public AssetAffectation get(User user, Long id) {
        return repository.findById(id).filter(a -> a.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new AssetAffectationNotFoundException(id));
    }

    @Transactional
    public AssetAffectation setEnabled(User user, Long id, boolean enabled) {
        AssetAffectation a = get(user, id);
        boolean changed = a.isEnabled() != enabled;
        a.setEnabled(enabled);
        a.setUpdatedAt(clock.now());
        AssetAffectation saved = repository.save(a);
        if (changed) {
            eventService.record(user, saved.getAssetSymbol(),
                    enabled ? RainbowLivePresetEventType.ENABLE : RainbowLivePresetEventType.DISABLE, saved, saved, null);
        }
        log.info("Affectation id={} user={} actif={} enabled={}", saved.getId(), user.getId(), saved.getAssetSymbol(), enabled);
        return saved;
    }

    /**
     * Installe les presets de l'Affectation (utilisateur, actif), créée si absente. Chaque preset doit être de l'actif et
     * être SYSTEM ou appartenir à l'utilisateur. Le moteur est réinitialisé (états purgés) si l'un des trois change.
     */
    @Transactional
    public AssetAffectation assign(User user, String assetSymbol, Long trendPresetId, Long rainbowUpId, Long rainbowDownId) {
        TrendPreset trend = trendRepository.findById(trendPresetId)
                .orElseThrow(() -> new IllegalArgumentException("Preset Trend introuvable : " + trendPresetId));
        RainbowPreset up = rainbowPreset(rainbowUpId);
        RainbowPreset down = rainbowPreset(rainbowDownId);
        requireUsable(user, assetSymbol, trend.getAssetSymbol(), trend.getOrigin(), trend.getOwner(), "Trend " + trend.getName());
        requireUsable(user, assetSymbol, up.getAssetSymbol(), up.getOrigin(), up.getOwner(), "Rainbow " + up.getName());
        requireUsable(user, assetSymbol, down.getAssetSymbol(), down.getOrigin(), down.getOwner(), "Rainbow " + down.getName());

        Instant now = clock.now();
        AssetAffectation a = repository.findByUserAndAssetSymbol(user, assetSymbol).orElseGet(() -> AssetAffectation.builder()
                .user(user).assetSymbol(assetSymbol).enabled(false).createdAt(now).updatedAt(now).build());
        String before = a.getId() == null ? null : a.label();
        boolean unchanged = a.getId() != null && sameId(a.getTrendPreset(), trend.getId()) && sameId(a.getRainbowUp(), up.getId())
                && sameId(a.getRainbowDown(), down.getId());
        a.setTrendPreset(trend);
        a.setRainbowUp(up);
        a.setRainbowDown(down);
        a.setUpdatedAt(now);
        AssetAffectation saved = repository.save(a);
        if (!unchanged) {
            if (before != null) {
                stateRepository.deleteByAffectation(saved);
            }
            eventService.record(user, assetSymbol, RainbowLivePresetEventType.LIVE_SWITCH, before, saved,
                    before == null ? "Presets définis" : "Presets modifiés : moteur réinitialisé");
            log.info("Affectation id={} user={} actif={} -> {} (moteur réinitialisé)", saved.getId(), user.getId(), assetSymbol, saved.label());
        }
        return saved;
    }

    private RainbowPreset rainbowPreset(Long id) {
        return rainbowRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Preset Rainbow introuvable : " + id));
    }

    private static boolean sameId(Object preset, Long id) {
        if (preset instanceof TrendPreset t) {
            return t.getId().equals(id);
        }
        return preset instanceof RainbowPreset r && r.getId().equals(id);
    }

    private static void requireUsable(User user, String asset, String presetAsset, PresetOrigin origin, User owner, String label) {
        if (!asset.equals(presetAsset)) {
            throw new IllegalArgumentException("Le preset " + label + " est sur " + presetAsset + ", pas sur " + asset);
        }
        if (origin == PresetOrigin.USER && (owner == null || !owner.getId().equals(user.getId()))) {
            throw new IllegalArgumentException("Le preset " + label + " appartient à un autre utilisateur");
        }
    }
}
