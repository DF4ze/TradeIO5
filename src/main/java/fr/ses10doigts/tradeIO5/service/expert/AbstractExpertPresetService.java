package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.CreatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.PresetHeaderDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.UsageDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.ExpertPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.repository.dca.bench.ExpertPresetRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Règles communes aux presets Trend et Rainbow de l'Espace Expert : visibilité (SYSTEM pour tous, USER pour son
 * propriétaire seul, sinon 404), droits (SYSTEM modifiable par l'ADMIN seul, jamais supprimable), concurrence par
 * révision, unicité des noms, duplication / création, et conséquences d'une modification sur les Affectations qui
 * installent le preset (reset moteur + événement, audit pour un SYSTEM).
 */
public abstract class AbstractExpertPresetService<P extends ExpertPreset> {

    /** Emplacement d'un preset dans une Affectation. */
    public enum UsageRole { TREND, RAINBOW_UP, RAINBOW_DOWN }

    private static final String COPY_SUFFIX = " (copie)";

    protected final ExpertPresetRepository<P> repository;
    protected final ExpertPresetSupport support;

    protected AbstractExpertPresetService(ExpertPresetRepository<P> repository, ExpertPresetSupport support) {
        this.repository = repository;
        this.support = support;
    }

    /** Préfixe de la cible d'audit : {@code trend-preset} / {@code rainbow-preset}. */
    protected abstract String kind();

    /** Affectations (tous utilisateurs) qui installent ce preset. */
    protected abstract List<AssetAffectation> affectationsUsing(P preset);

    protected abstract List<UsageRole> rolesIn(AssetAffectation affectation, P preset);

    /** Réglages à plat, pour détecter ce qui change. */
    protected abstract Map<String, Object> settings(P preset);

    /** Nouveau preset USER (non persisté) aux réglages de {@code source}. */
    protected abstract P copyOf(P source, String asset, String name, User owner, String provider, Instant now);

    /** Preset système de base de l'actif, point de départ d'une création sans modèle. */
    protected abstract P baseFor(String asset);

    @Transactional(readOnly = true)
    public List<PresetHeaderDto> list(ExpertContext ctx, String asset) {
        String symbol = support.getAssets().requireAsset(asset);
        List<P> presets = new ArrayList<>(repository.findByAssetSymbolAndOrigin(symbol, PresetOrigin.SYSTEM));
        presets.addAll(repository.findByAssetSymbolAndOwner(symbol, ctx.user()));
        presets.sort(Comparator.comparing((P p) -> p.getOrigin()).thenComparing(P::getName));
        AssetAffectation mine = support.getAffectationRepository().findByUserAndAssetSymbol(ctx.user(), symbol).orElse(null);
        return presets.stream().map(p -> header(ctx, p, mine)).toList();
    }

    /** Preset visible de l'appelant ; inexistant ou USER d'un autre utilisateur ⇒ 404. */
    @Transactional(readOnly = true)
    public P get(ExpertContext ctx, Long id) {
        return visible(ctx, repository.findById(id), id);
    }

    @Transactional(readOnly = true)
    public PresetHeaderDto header(ExpertContext ctx, P preset) {
        return header(ctx, preset, support.getAffectationRepository().findByUserAndAssetSymbol(ctx.user(), preset.getAssetSymbol()).orElse(null));
    }

    @Transactional
    public void delete(ExpertContext ctx, Long id) {
        P preset = visible(ctx, repository.findWithLockById(id), id);
        if (preset.getOrigin() == PresetOrigin.SYSTEM) {
            throw new ExpertApiException(ExpertApiException.Code.PRESET_SYSTEM_UNDELETABLE, "Un preset système ne se supprime pas");
        }
        List<UsageDto> usages = affectationsUsing(preset).stream()
                .flatMap(a -> rolesIn(a, preset).stream().map(r -> new UsageDto(a.getId(), a.getAssetSymbol(), r.name()))).toList();
        if (!usages.isEmpty()) {
            throw new ExpertApiException(ExpertApiException.Code.PRESET_IN_USE,
                    "Preset encore installé dans une Affectation : changez l'Affectation avant de le supprimer", usages);
        }
        repository.delete(preset);
    }

    /** Duplique un preset visible en preset USER de l'appelant, sur le même actif ou un autre ; jamais affecté d'office. */
    protected P duplicate(ExpertContext ctx, Long id, String targetAsset, String name) {
        P source = get(ctx, id);
        return copy(ctx, source, targetAsset == null ? source.getAssetSymbol() : targetAsset, name, null);
    }

    protected P create(ExpertContext ctx, CreatePresetRequest request) {
        String asset = support.getAssets().requireAsset(request.assetSymbol());
        support.getValidator().name(request.name());
        P source = request.copyFromId() == null ? baseFor(asset) : get(ctx, request.copyFromId());
        return copy(ctx, source, asset, request.name(), request.referenceProvider());
    }

    /**
     * Modification d'un preset : droits, révision lue, validation, application, puis — si les réglages changent — reset moteur
     * et événement de chaque Affectation concernée ; un preset SYSTEM est en plus tracé au journal admin.
     */
    protected P update(ExpertContext ctx, Long id, Integer revision, String name, String provider, Runnable validate, Consumer<P> apply) {
        P preset = visible(ctx, repository.findWithLockById(id), id);
        if (preset.getOrigin() == PresetOrigin.SYSTEM && !ctx.admin()) {
            throw new ExpertApiException(ExpertApiException.Code.PRESET_SYSTEM_READ_ONLY,
                    "Preset système : modification réservée à l'administrateur, dupliquez-le pour l'adapter");
        }
        if (revision == null) {
            throw new ExpertValidationException("revision", "obligatoire (celle lue)");
        }
        if (revision != preset.getRevision()) {
            throw new ExpertApiException(ExpertApiException.Code.PRESET_REVISION_CONFLICT,
                    "Preset modifié entre-temps : révision " + preset.getRevision() + " (reçue " + revision + ")",
                    Map.of("currentRevision", preset.getRevision()));
        }
        validate.run();
        String newName = support.getValidator().name(name);
        String asset = preset.getAssetSymbol();
        if (!newName.equals(preset.getName())) {
            requireNameFree(preset.getOrigin(), ctx.user(), asset, newName);
        }
        String newProvider = ExpertAssets.normalizeProvider(provider);
        if (!Objects.equals(newProvider, preset.getReferenceProvider())) {
            newProvider = support.getAssets().requireProvider(asset, newProvider);
        }

        Map<String, Object> before = settings(preset);
        String nameBefore = preset.getName();
        String providerBefore = preset.getReferenceProvider();
        List<AssetAffectation> used = affectationsUsing(preset);
        Map<Long, String> labelsBefore = new LinkedHashMap<>();
        used.forEach(a -> labelsBefore.put(a.getId(), a.label()));

        apply.accept(preset);
        Map<String, String> changes = PresetSettings.changes(before, settings(preset));
        boolean identityChanged = !newName.equals(nameBefore) || !Objects.equals(newProvider, providerBefore);
        if (changes.isEmpty() && !identityChanged) {
            return preset;
        }
        preset.setName(newName);
        preset.setReferenceProvider(newProvider);
        preset.setRevision(preset.getRevision() + 1);
        preset.setUpdatedAt(support.getClock().now());
        repository.save(preset);

        boolean reset = !changes.isEmpty();
        if (reset) {
            resetEngines(preset, used, labelsBefore);
        }
        if (preset.getOrigin() == PresetOrigin.SYSTEM) {
            support.getJournal().record(ctx.user().getUsername(), AdminAction.PRESET, kind() + ":" + preset.getId(),
                    AdminActionLogService.payload("name", newName, "nameBefore", newName.equals(nameBefore) ? null : nameBefore,
                            "providerBefore", Objects.equals(newProvider, providerBefore) ? null : providerBefore,
                            "provider", Objects.equals(newProvider, providerBefore) ? null : newProvider,
                            "revision", preset.getRevision(), "affectations", used.size(), "reset", reset,
                            "changes", changes.isEmpty() ? null : changes),
                    AdminActionOutcome.OK);
        }
        return preset;
    }

    private void resetEngines(P preset, List<AssetAffectation> used, Map<Long, String> labelsBefore) {
        String reason = "Preset modifié : " + preset.getName() + " (révision " + preset.getRevision() + "), moteur réinitialisé";
        for (AssetAffectation a : used) {
            support.getStateRepository().deleteByAffectation(a);
            support.getEventService().record(a.getUser(), a.getAssetSymbol(), RainbowLivePresetEventType.LIVE_SWITCH,
                    labelsBefore.get(a.getId()), a, reason);
        }
    }

    private P copy(ExpertContext ctx, P source, String targetAsset, String requestedName, String explicitProvider) {
        String asset = support.getAssets().requireAsset(targetAsset);
        String name;
        if (requestedName == null || requestedName.isBlank()) {
            name = uniqueName(ctx.user(), asset, source.getName());
        } else {
            name = support.getValidator().name(requestedName);
            requireNameFree(PresetOrigin.USER, ctx.user(), asset, name);
        }
        String provider = ExpertAssets.normalizeProvider(explicitProvider);
        if (provider != null) {
            provider = support.getAssets().requireProvider(asset, provider);
        } else if (source.getReferenceProvider() != null && support.getAssets().providerNames(asset).contains(source.getReferenceProvider())) {
            provider = source.getReferenceProvider();
        }
        return repository.save(copyOf(source, asset, name, ctx.user(), provider, support.getClock().now()));
    }

    /** Nom dérivé de l'original, suffixé d'un entier tant qu'il est pris pour (propriétaire, actif). */
    private String uniqueName(User owner, String asset, String sourceName) {
        int room = ExpertPresetValidator.NAME_MAX - COPY_SUFFIX.length() - String.valueOf(Integer.MAX_VALUE).length() - 1;
        String base = (sourceName.length() > room ? sourceName.substring(0, room) : sourceName) + COPY_SUFFIX;
        String candidate = base;
        for (int n = 2; repository.existsByAssetSymbolAndOwnerAndName(asset, owner, candidate); n++) {
            candidate = base + " " + n;
        }
        return candidate;
    }

    private void requireNameFree(PresetOrigin origin, User owner, String asset, String name) {
        boolean taken = origin == PresetOrigin.SYSTEM
                ? repository.existsByAssetSymbolAndOriginAndName(asset, PresetOrigin.SYSTEM, name)
                : repository.existsByAssetSymbolAndOwnerAndName(asset, owner, name);
        if (taken) {
            throw new ExpertValidationException("name", "déjà utilisé pour l'actif " + asset);
        }
    }

    private P visible(ExpertContext ctx, Optional<P> found, Long id) {
        return found.filter(p -> p.getOrigin() == PresetOrigin.SYSTEM || ctx.isOwner(p.getOwner()))
                .orElseThrow(() -> new ExpertApiException(ExpertApiException.Code.PRESET_NOT_FOUND, "Preset introuvable : " + id));
    }

    private PresetHeaderDto header(ExpertContext ctx, P p, AssetAffectation mine) {
        boolean system = p.getOrigin() == PresetOrigin.SYSTEM;
        List<UsageDto> usedBy = mine == null ? List.of()
                : rolesIn(mine, p).stream().map(r -> new UsageDto(mine.getId(), mine.getAssetSymbol(), r.name())).toList();
        return new PresetHeaderDto(p.getId(), p.getAssetSymbol(), p.getName(), p.getOrigin().name(), ctx.isOwner(p.getOwner()),
                p.getBaseRole() == null ? null : p.getBaseRole().name(), p.getReferenceProvider(), p.getRevision(),
                system ? ctx.admin() : ctx.isOwner(p.getOwner()), !system && ctx.isOwner(p.getOwner()), usedBy,
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
