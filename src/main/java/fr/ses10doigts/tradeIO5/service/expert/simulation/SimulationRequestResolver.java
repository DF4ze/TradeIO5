package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.RainbowDraft;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.RainbowRef;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.TrendDraft;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.TrendRef;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.expert.ExpertPresetValidator;
import fr.ses10doigts.tradeIO5.service.expert.RainbowPresetService;
import fr.ses10doigts.tradeIO5.service.expert.TrendPresetService;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Résout une {@link SimulationRequest} : presets par {@code id} (système ou propres à l'appelant, 404 sinon, mêmes droits que les
 * endpoints de presets) ou brouillons validés par {@link ExpertPresetValidator} ; aucune écriture. Référence omise ⇒ celle de
 * l'Affectation de l'utilisateur sur l'actif, à défaut le preset système de base.
 */
@Component
@RequiredArgsConstructor
public class SimulationRequestResolver {

    private static final String DRAFT_LABEL = "brouillon";

    private final TrendPresetService trendPresets;
    private final RainbowPresetService rainbowPresets;
    private final TrendPresetRepository trendRepository;
    private final RainbowPresetRepository rainbowRepository;
    private final AssetAffectationRepository affectationRepository;
    private final ExpertPresetValidator validator;

    private record TrendSettings(TrendMixCalculator.Params params, RangeMapping mapping, String label, String provider) {
    }

    private record RainbowSettings(RainbowAtrParamSet set, int analysisMonths, String provider) {
    }

    @Transactional(readOnly = true)
    public ResolvedSimulation resolve(ExpertContext ctx, SimulationType type, String asset, SimulationRequest request) {
        AssetAffectation affectation = affectationRepository.findByUserAndAssetSymbol(ctx.user(), asset).orElse(null);
        TrendSettings trend = trend(ctx, asset, request.trend(), affectation);
        return switch (type) {
            case TREND -> new ResolvedSimulation(type, asset, trend.params(), trend.mapping(), trend.label(), null, null, null,
                    SimulationEligibility.requiredPoints(trend.params(), null, null), trend.provider());
            case RAINBOW -> rainbow(ctx, asset, request, affectation, trend);
            case AFFECTATION -> affectation(ctx, asset, request, affectation, trend);
        };
    }

    private ResolvedSimulation rainbow(ExpertContext ctx, String asset, SimulationRequest request, AssetAffectation affectation,
                                       TrendSettings trend) {
        TrendRegime signal = signal(request.signal());
        RainbowRef ref = request.rainbow();
        if (signal == null && ref != null && ref.id() != null) {
            signal = slotOf(affectation, ref.id());
        }
        if (signal == null) {
            throw new IllegalArgumentException("signal obligatoire (UP ou DOWN) : le preset Rainbow est neutre");
        }
        RainbowSettings rainbow = rainbow(ctx, asset, ref, defaultRainbow(affectation, signal), signal == TrendRegime.UP
                ? BasePresetRole.RAINBOW_BULL : BasePresetRole.RAINBOW_BEAR);
        RainbowAtrParamSet up = signal == TrendRegime.UP ? rainbow.set() : null;
        RainbowAtrParamSet down = signal == TrendRegime.DOWN ? rainbow.set() : null;
        return new ResolvedSimulation(SimulationType.RAINBOW, asset, trend.params(), trend.mapping(), trend.label(), up, down, signal,
                SimulationEligibility.requiredPoints(trend.params(), rainbow.set().tuning(), rainbow.analysisMonths()),
                firstNonNull(rainbow.provider(), trend.provider()));
    }

    private ResolvedSimulation affectation(ExpertContext ctx, String asset, SimulationRequest request, AssetAffectation affectation,
                                           TrendSettings trend) {
        RainbowSettings up = rainbow(ctx, asset, request.rainbowUp(), defaultRainbow(affectation, TrendRegime.UP), BasePresetRole.RAINBOW_BULL);
        RainbowSettings down = rainbow(ctx, asset, request.rainbowDown(), defaultRainbow(affectation, TrendRegime.DOWN), BasePresetRole.RAINBOW_BEAR);
        int required = Math.max(
                SimulationEligibility.requiredPoints(trend.params(), up.set().tuning(), up.analysisMonths()),
                SimulationEligibility.requiredPoints(trend.params(), down.set().tuning(), down.analysisMonths()));
        return new ResolvedSimulation(SimulationType.AFFECTATION, asset, trend.params(), trend.mapping(), trend.label(), up.set(),
                down.set(), null, required, firstNonNull(trend.provider(), up.provider(), down.provider()));
    }

    // ---------------------------------------------------------------- Trend

    private TrendSettings trend(ExpertContext ctx, String asset, TrendRef ref, AssetAffectation affectation) {
        if (ref != null && ref.id() != null && ref.draft() != null) {
            throw new IllegalArgumentException("trend : id OU brouillon, pas les deux");
        }
        if (ref != null && ref.draft() != null) {
            TrendDraft draft = ref.draft();
            validator.trend(draft.trend(), draft.rangeMapping());
            return new TrendSettings(draft.trend(), RangeMapping.valueOf(draft.rangeMapping()), DRAFT_LABEL, null);
        }
        TrendPreset preset;
        if (ref != null && ref.id() != null) {
            preset = trendPresets.get(ctx, ref.id());
            requireAsset(asset, preset.getAssetSymbol(), "trend");
        } else if (affectation != null && affectation.getTrendPreset() != null) {
            preset = affectation.getTrendPreset();
        } else {
            preset = trendRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.TREND).orElseThrow(
                    () -> new ExpertApiException(ExpertApiException.Code.PRESET_NOT_FOUND, "Preset Trend de base absent pour " + asset));
        }
        return new TrendSettings(preset.toParams(), RangeMapping.valueOf(preset.getRangeMapping()), preset.getName(),
                preset.getReferenceProvider());
    }

    // ---------------------------------------------------------------- Rainbow

    private RainbowSettings rainbow(ExpertContext ctx, String asset, RainbowRef ref, Optional<RainbowPreset> fromAffectation,
                                    BasePresetRole baseRole) {
        if (ref != null && ref.id() != null && ref.draft() != null) {
            throw new IllegalArgumentException("rainbow : id OU brouillon, pas les deux");
        }
        if (ref != null && ref.draft() != null) {
            RainbowDraft draft = ref.draft();
            validator.rainbow(draft.analysisWindowMonths(), draft.tuning(), draft.globals());
            return new RainbowSettings(new RainbowAtrParamSet(DRAFT_LABEL, draft.tuning(), draft.globals()),
                    draft.analysisWindowMonths(), null);
        }
        RainbowPreset preset;
        if (ref != null && ref.id() != null) {
            preset = rainbowPresets.get(ctx, ref.id());
            requireAsset(asset, preset.getAssetSymbol(), "rainbow");
        } else {
            preset = fromAffectation.orElseGet(() -> rainbowRepository.findByAssetSymbolAndBaseRole(asset, baseRole).orElseThrow(
                    () -> new ExpertApiException(ExpertApiException.Code.PRESET_NOT_FOUND, "Preset Rainbow de base absent pour " + asset)));
        }
        return new RainbowSettings(new RainbowAtrParamSet(preset.getName(), preset.getConfig().toTuning(), preset.getConfig().toGlobals()),
                preset.getAnalysisWindowMonths(), preset.getReferenceProvider());
    }

    private static Optional<RainbowPreset> defaultRainbow(AssetAffectation affectation, TrendRegime slot) {
        if (affectation == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(slot == TrendRegime.UP ? affectation.getRainbowUp() : affectation.getRainbowDown());
    }

    /** Emplacement du preset dans l'Affectation ; nul s'il n'y est pas ou occupe les deux emplacements (ambigu). */
    private static TrendRegime slotOf(AssetAffectation affectation, Long presetId) {
        if (affectation == null) {
            return null;
        }
        boolean up = affectation.getRainbowUp() != null && Objects.equals(affectation.getRainbowUp().getId(), presetId);
        boolean down = affectation.getRainbowDown() != null && Objects.equals(affectation.getRainbowDown().getId(), presetId);
        return up == down ? null : up ? TrendRegime.UP : TrendRegime.DOWN;
    }

    private static TrendRegime signal(String signal) {
        if (signal == null || signal.isBlank()) {
            return null;
        }
        return switch (signal.trim().toUpperCase(Locale.ROOT)) {
            case "UP" -> TrendRegime.UP;
            case "DOWN" -> TrendRegime.DOWN;
            default -> throw new IllegalArgumentException("signal : UP ou DOWN attendu (reçu " + signal + ")");
        };
    }

    private static void requireAsset(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " : preset de l'actif " + actual + ", simulation demandée sur " + expected);
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
