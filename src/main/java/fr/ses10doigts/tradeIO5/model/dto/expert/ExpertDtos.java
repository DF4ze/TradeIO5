package fr.ses10doigts.tradeIO5.model.dto.expert;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.time.Instant;
import java.util.List;

/** DTO de l'API Espace Expert ({@code /api/expert/**}) : jamais d'entité JPA exposée. */
public final class ExpertDtos {

    private ExpertDtos() {
    }

    /** Provider qui porte un actif ({@code AssetProvider}). */
    public record ProviderDto(String provider, Integer maxHorizonDays) {
    }

    /**
     * Actif proposé. {@code exploitable} = Affectation complète de l'utilisateur ; {@code simulable} = au moins un provider actif ;
     * {@code eligibility} = historique D1 requis par les presets de l'utilisateur et bougies déjà en cache par provider (aucun fetch).
     */
    public record AssetDto(String symbol, String name, List<ProviderDto> providers, boolean exploitable, boolean simulable,
                           ExpertSimulationDtos.EligibilityDto eligibility) {
    }

    /** Emplacement d'un preset dans une Affectation ; {@code enabled} = moteur de l'Affectation allumé (engagé). */
    public record UsageDto(Long affectationId, String assetSymbol, String role, boolean enabled) {
    }

    /** Impact d'une modification d'un preset SYSTEM : comptes seulement (aucune identité), tous utilisateurs confondus. */
    public record ImpactDto(int affectations, int activeEngines) {
    }

    /** En-tête commun d'un preset Trend / Rainbow. {@code usedBy} = Affectations de l'utilisateur courant qui l'installent. */
    public record PresetHeaderDto(Long id, String assetSymbol, String name, String origin, boolean mine, String baseRole,
                                  String referenceProvider, int revision, boolean editable, boolean deletable,
                                  List<UsageDto> usedBy, Instant createdAt, Instant updatedAt) {
    }

    public record TrendPresetDto(PresetHeaderDto header, TrendMixCalculator.Params trend, String rangeMapping) {
    }

    public record RainbowPresetDto(PresetHeaderDto header, int analysisWindowMonths, RainbowAtrTuning tuning,
                                   RainbowAtrGlobals globals) {
    }

    /** Création d'un preset USER : copie de {@code copyFromId} (visible), sinon du preset de base de l'actif. */
    public record CreatePresetRequest(String assetSymbol, String name, Long copyFromId, String referenceProvider) {
    }

    /** Duplication : actif cible (défaut = celui de l'original) et nom (défaut dérivé de l'original, unique). */
    public record DuplicatePresetRequest(String assetSymbol, String name) {
    }

    /** Modification : {@code revision} = celle lue (décalage ⇒ 409). */
    public record TrendPresetRequest(String name, String referenceProvider, TrendMixCalculator.Params trend, String rangeMapping,
                                     Integer revision) {
    }

    public record RainbowPresetRequest(String name, String referenceProvider, Integer analysisWindowMonths, RainbowAtrTuning tuning,
                                       RainbowAtrGlobals globals, Integer revision) {
    }

    public record PresetRefDto(Long id, String name, String origin, String referenceProvider) {
    }

    /** Écart entre le provider de référence d'un preset de l'Affectation et ceux des liens de l'utilisateur sur l'actif (information). */
    public record ProviderGapDto(String role, Long presetId, String presetName, String referenceProvider,
                                 List<String> bindingProviders, boolean gap) {
    }

    public record AffectationDto(Long id, String assetSymbol, boolean enabled, boolean complete, PresetRefDto trend,
                                 PresetRefDto rainbowUp, PresetRefDto rainbowDown, List<ProviderGapDto> providerGaps,
                                 Instant updatedAt) {
    }

    public record AssignRequest(Long trendPresetId, Long rainbowUpId, Long rainbowDownId) {
    }
}
