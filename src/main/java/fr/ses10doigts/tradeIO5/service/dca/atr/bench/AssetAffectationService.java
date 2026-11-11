package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
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
 * utilisateur = une instance Système par actif sur les presets de base ; l'utilisateur en crée d'autres (nommées). Changer
 * les presets d'une Affectation, ou le lien qui la porte, purge son état moteur : la prochaine passe repart des nouveaux presets (rejeu de la fenêtre), sans reprendre
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
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLivePresetEventService eventService;
    private final fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository runRepository;
    private final DomainClock clock;

    /** Nom de l'instance Système d'un actif (« BTC Système »). */
    public static String systemName(String asset) {
        return asset + " Système";
    }

    /**
     * Instance Système (presets de base, non modifiable) pour chaque actif où l'utilisateur n'en a pas. Une Affectation
     * antérieure dont les trois presets sont SYSTEM est adoptée (marquée Système et nommée) au lieu d'être dupliquée.
     */
    @Transactional
    public List<AssetAffectation> ensureAffectations(User user) {
        List<AssetAffectation> created = new ArrayList<>();
        Instant now = clock.now();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            if (repository.findByUserAndAssetSymbolAndSystemTrue(user, asset).isPresent()) {
                continue;
            }
            TrendPreset trend = trendRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.TREND).orElse(null);
            RainbowPreset up = rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BULL).orElse(null);
            RainbowPreset down = rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BEAR).orElse(null);
            if (trend == null || up == null || down == null) {
                log.warn("Presets de base absents pour {} : Affectation de user={} non créée", asset, user.getId());
                continue;
            }
            AssetAffectation adopted = repository.findByUserAndAssetSymbolOrderByIdAsc(user, asset).stream()
                    .filter(a -> a.getName() == null && onBasePresets(a, trend, up, down)).findFirst().orElse(null);
            if (adopted != null) {
                adopted.setSystem(true);
                adopted.setName(systemName(asset));
                created.add(repository.save(adopted));
                continue;
            }
            created.add(repository.save(AssetAffectation.builder().user(user).assetSymbol(asset).name(systemName(asset)).system(true)
                    .trendPreset(trend).rainbowUp(up).rainbowDown(down).createdAt(now).updatedAt(now).build()));
        }
        if (!created.isEmpty()) {
            log.info("Affectations Système créées pour user={} : {}", user.getId(), created.size());
        }
        return created;
    }

    private static boolean onBasePresets(AssetAffectation a, TrendPreset trend, RainbowPreset up, RainbowPreset down) {
        return a.isComplete() && a.getTrendPreset().getId().equals(trend.getId()) && a.getRainbowUp().getId().equals(up.getId())
                && a.getRainbowDown().getId().equals(down.getId());
    }

    /** Instance Système de l'utilisateur sur l'actif, créée sur les presets de base si absente. */
    @Transactional
    public AssetAffectation forAsset(User user, String assetSymbol) {
        ensureAffectations(user);
        return repository.findByUserAndAssetSymbolAndSystemTrue(user, assetSymbol)
                .orElseThrow(() -> new IllegalStateException("Aucune Affectation possible pour " + assetSymbol + " (presets de base absents)"));
    }

    /** Affectations de l'utilisateur : par actif, Système d'abord puis par nom. */
    @Transactional(readOnly = true)
    public List<AssetAffectation> list(User user) {
        return repository.findByUser(user).stream().sorted(ORDER).toList();
    }

    @Transactional(readOnly = true)
    public List<AssetAffectation> listForAsset(User user, String assetSymbol) {
        return repository.findByUserAndAssetSymbolOrderByIdAsc(user, assetSymbol).stream().sorted(ORDER).toList();
    }

    private static final java.util.Comparator<AssetAffectation> ORDER = java.util.Comparator
            .<AssetAffectation>comparingInt(a -> RainbowLiveDefaultPresets.ASSETS.indexOf(a.getAssetSymbol()))
            .thenComparing(a -> !a.isSystem()).thenComparing(AssetAffectation::displayName);

    /** Affectation de l'utilisateur ; inexistante ou d'un autre utilisateur ⇒ {@link AssetAffectationNotFoundException}. */
    @Transactional(readOnly = true)
    public AssetAffectation get(User user, Long id) {
        return repository.findById(id).filter(a -> a.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new AssetAffectationNotFoundException(id));
    }

    /** Vrai si au moins un lien non archivé référence l'Affectation : c'est ce qui la fait jouer par la passe. */
    @Transactional(readOnly = true)
    public boolean isActive(AssetAffectation affectation) {
        return bindingRepository.existsByAffectationAndArchivedAtIsNull(affectation);
    }

    /** Affectations référencées par un lien non archivé de l'utilisateur (complètes ou non), triées par actif. */
    @Transactional(readOnly = true)
    public List<AssetAffectation> linked(User user) {
        return bindingRepository.findActiveAffectationsByUser(user).stream()
                .sorted(java.util.Comparator.comparingInt(a -> RainbowLiveDefaultPresets.ASSETS.indexOf(a.getAssetSymbol()))).toList();
    }

    /** Affectations jouées pour l'utilisateur : référencées par un lien non archivé et complètes. */
    @Transactional(readOnly = true)
    public List<AssetAffectation> active(User user) {
        return linked(user).stream().filter(AssetAffectation::isComplete).toList();
    }

    /**
     * Affectation utilisateur (jamais partagée) sur l'actif. Nom unique par (utilisateur, actif) ; chaque preset doit être de
     * l'actif et être SYSTEM ou appartenir à l'utilisateur.
     */
    @Transactional
    public AssetAffectation create(User user, String assetSymbol, String name, Long trendPresetId, Long rainbowUpId, Long rainbowDownId) {
        String clean = requireName(user, assetSymbol, name, null);
        Presets p = presets(user, assetSymbol, trendPresetId, rainbowUpId, rainbowDownId);
        Instant now = clock.now();
        AssetAffectation saved = repository.save(AssetAffectation.builder().user(user).assetSymbol(assetSymbol).name(clean)
                .trendPreset(p.trend()).rainbowUp(p.up()).rainbowDown(p.down()).createdAt(now).updatedAt(now).build());
        log.info("Affectation créée id={} user={} actif={} nom={}", saved.getId(), user.getId(), assetSymbol, clean);
        return saved;
    }

    /**
     * Renomme et/ou change les presets d'une Affectation utilisateur ({@code name} nul = inchangé). Si l'un des trois presets
     * change, le moteur est réinitialisé (états purgés) et la prochaine passe rejoue l'historique. L'instance Système est en
     * lecture seule.
     */
    @Transactional
    public AssetAffectation update(User user, Long id, String name, Long trendPresetId, Long rainbowUpId, Long rainbowDownId) {
        AssetAffectation a = get(user, id);
        requireEditable(a);
        String assetSymbol = a.getAssetSymbol();
        Presets p = presets(user, assetSymbol, trendPresetId, rainbowUpId, rainbowDownId);
        String clean = name == null ? a.getName() : requireName(user, assetSymbol, name, a);
        String before = a.displayName();
        boolean unchanged = a.isComplete() && sameId(a.getTrendPreset(), p.trend().getId()) && sameId(a.getRainbowUp(), p.up().getId())
                && sameId(a.getRainbowDown(), p.down().getId());
        a.setName(clean);
        a.setTrendPreset(p.trend());
        a.setRainbowUp(p.up());
        a.setRainbowDown(p.down());
        a.setUpdatedAt(clock.now());
        AssetAffectation saved = repository.save(a);
        if (!unchanged) {
            resetEngine(saved);
            eventService.record(user, assetSymbol, RainbowLivePresetEventType.LIVE_SWITCH, before, saved, "Presets modifiés : moteur réinitialisé");
            log.info("Affectation id={} user={} actif={} -> {} (moteur réinitialisé)", saved.getId(), user.getId(), assetSymbol, saved.displayName());
        }
        return saved;
    }

    /** Supprime une Affectation utilisateur jamais liée ni jouée ; sinon {@link AffectationRuleException} (IN_USE). */
    @Transactional
    public void delete(User user, Long id) {
        AssetAffectation a = get(user, id);
        requireEditable(a);
        if (!bindingRepository.findAllByAffectation(a).isEmpty() || runRepository.countByAffectation(a) > 0) {
            throw new AffectationRuleException(AffectationRuleException.Reason.IN_USE,
                    "Affectation « " + a.displayName() + " » déjà utilisée par un lien : suppression impossible");
        }
        stateRepository.deleteByAffectation(a);
        repository.delete(a);
        log.info("Affectation supprimée id={} user={} actif={}", id, user.getId(), a.getAssetSymbol());
    }

    /** Affectation sur laquelle un lien de l'actif peut être posé : à l'utilisateur, du même actif, complète. */
    @Transactional(readOnly = true)
    public AssetAffectation requireLinkable(User user, String assetSymbol, Long id) {
        AssetAffectation a = get(user, id);
        if (!a.getAssetSymbol().equals(assetSymbol)) {
            throw new IllegalArgumentException("L'Affectation « " + a.displayName() + " » est sur " + a.getAssetSymbol() + ", pas sur " + assetSymbol);
        }
        if (!a.isComplete()) {
            throw new IllegalArgumentException("L'Affectation « " + a.displayName() + " » est incomplète");
        }
        return a;
    }

    /**
     * Un lien passe de {@code before} à {@code after} : moteur de {@code after} réinitialisé (purge des états ; la passe suivante
     * rejoue l'historique, de façon déterministe) et événement LIVE_SWITCH. Aucun ordre n'est bloqué.
     */
    @Transactional
    public void switchLink(User user, AssetAffectation before, AssetAffectation after) {
        resetEngine(after);
        eventService.record(user, after.getAssetSymbol(), RainbowLivePresetEventType.LIVE_SWITCH, before, after,
                "Lien basculé de « " + before.displayName() + " » vers « " + after.displayName() + " » : moteur réinitialisé");
        log.info("Lien basculé user={} actif={} : {} -> {} (moteur réinitialisé)", user.getId(), after.getAssetSymbol(),
                before.displayName(), after.displayName());
    }

    private void resetEngine(AssetAffectation a) {
        stateRepository.deleteByAffectation(a);
    }

    private static void requireEditable(AssetAffectation a) {
        if (a.isSystem()) {
            throw new AffectationRuleException(AffectationRuleException.Reason.SYSTEM_READ_ONLY,
                    "Affectation Système « " + a.displayName() + " » : lecture seule");
        }
    }

    /** Nom nettoyé (1 à 64 caractères), unique par (utilisateur, actif) hors l'Affectation {@code self}. */
    private String requireName(User user, String assetSymbol, String name, AssetAffectation self) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 64) {
            throw new IllegalArgumentException("Nom d'Affectation obligatoire (1 à 64 caractères)");
        }
        boolean taken = repository.findByUserAndAssetSymbolOrderByIdAsc(user, assetSymbol).stream()
                .anyMatch(o -> (self == null || !o.getId().equals(self.getId())) && clean.equalsIgnoreCase(o.displayName()));
        if (taken) {
            throw new AffectationRuleException(AffectationRuleException.Reason.NAME_TAKEN,
                    "Une Affectation « " + clean + " » existe déjà sur " + assetSymbol);
        }
        return clean;
    }

    private record Presets(TrendPreset trend, RainbowPreset up, RainbowPreset down) {
    }

    private Presets presets(User user, String assetSymbol, Long trendPresetId, Long rainbowUpId, Long rainbowDownId) {
        TrendPreset trend = trendRepository.findById(trendPresetId)
                .orElseThrow(() -> new IllegalArgumentException("Preset Trend introuvable : " + trendPresetId));
        RainbowPreset up = rainbowPreset(rainbowUpId);
        RainbowPreset down = rainbowPreset(rainbowDownId);
        requireUsable(user, assetSymbol, trend.getAssetSymbol(), trend.getOrigin(), trend.getOwner(), "Trend " + trend.getName());
        requireUsable(user, assetSymbol, up.getAssetSymbol(), up.getOrigin(), up.getOwner(), "Rainbow " + up.getName());
        requireUsable(user, assetSymbol, down.getAssetSymbol(), down.getOrigin(), down.getOwner(), "Rainbow " + down.getName());
        return new Presets(trend, up, down);
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
