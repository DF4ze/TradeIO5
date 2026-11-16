package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicySpec;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fabrique des politiques manuelles à partir d'un {@link PolicySpec} ; validation par champ ({@link ExpertValidationException}). */
public final class ModulationPolicies {

    public static final String CONSTANT = "CONSTANT";
    public static final String REGIME_BAND = "REGIME_BAND";
    public static final String EXPOSURE_GAP_BIAS = "EXPOSURE_GAP_BIAS";
    public static final String BEAR_DERISK_RAMP = "BEAR_DERISK_RAMP";

    private static final Map<String, List<String>> PARAMS = Map.of(
            CONSTANT, List.of(),
            REGIME_BAND, List.of("bullMin", "bullMax", "bearMin", "bearMax"),
            EXPOSURE_GAP_BIAS, List.of("gain"),
            BEAR_DERISK_RAMP, List.of("bearTarget", "rampDays"));
    private static final double MAX_GAIN = 10.0;
    private static final int MAX_RAMP_DAYS = 365;

    private ModulationPolicies() {
    }

    public static ModulationPolicy from(PolicySpec spec) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (spec == null || spec.type() == null || !PARAMS.containsKey(spec.type())) {
            throw new ExpertValidationException("policy.type", "inconnu (attendu : " + String.join(", ", PARAMS.keySet().stream().sorted().toList()) + ")");
        }
        Map<String, Double> params = spec.params() == null ? Map.of() : spec.params();
        Set<String> expected = Set.copyOf(PARAMS.get(spec.type()));
        params.keySet().stream().sorted().filter(k -> !expected.contains(k))
                .forEach(k -> errors.put("policy.params." + k, "paramètre inconnu pour " + spec.type()));
        for (String name : PARAMS.get(spec.type())) {
            Double v = params.get(name);
            if (v == null || !Double.isFinite(v)) {
                errors.put("policy.params." + name, "obligatoire (nombre fini)");
            }
        }
        if (!errors.isEmpty()) {
            throw new ExpertValidationException(errors);
        }
        ModulationPolicy policy = switch (spec.type()) {
            case REGIME_BAND -> regimeBand(params, errors);
            case EXPOSURE_GAP_BIAS -> exposureGap(params, errors);
            case BEAR_DERISK_RAMP -> bearRamp(params, errors);
            default -> new ConstantPolicy();
        };
        if (!errors.isEmpty()) {
            throw new ExpertValidationException(errors);
        }
        return policy;
    }

    private static ModulationPolicy regimeBand(Map<String, Double> p, Map<String, String> errors) {
        band(p, "bull", errors);
        band(p, "bear", errors);
        return new RegimeBandPolicy(p.get("bullMin"), p.get("bullMax"), p.get("bearMin"), p.get("bearMax"));
    }

    private static void band(Map<String, Double> p, String prefix, Map<String, String> errors) {
        double min = p.get(prefix + "Min");
        double max = p.get(prefix + "Max");
        percent(prefix + "Min", min, errors);
        percent(prefix + "Max", max, errors);
        if (min >= max) {
            errors.put("policy.params." + prefix + "Max", "doit être > " + prefix + "Min");
        }
    }

    private static ModulationPolicy exposureGap(Map<String, Double> p, Map<String, String> errors) {
        double gain = p.get("gain");
        if (gain <= 0 || gain > MAX_GAIN) {
            errors.put("policy.params.gain", "doit être dans ]0 ; " + MAX_GAIN + "]");
        }
        return new ExposureGapBiasPolicy(gain, ModulationDefaults.LIMITS.get(ModulationDefaults.BIAS_MAX).max().doubleValue());
    }

    private static ModulationPolicy bearRamp(Map<String, Double> p, Map<String, String> errors) {
        percent("bearTarget", p.get("bearTarget"), errors);
        double days = p.get("rampDays");
        if (days != Math.rint(days) || days < 1 || days > MAX_RAMP_DAYS) {
            errors.put("policy.params.rampDays", "entier dans [1 ; " + MAX_RAMP_DAYS + "]");
        }
        return new BearDeriskRampPolicy(p.get("bearTarget"), (int) days);
    }

    private static void percent(String name, double v, Map<String, String> errors) {
        if (v < ModulationDefaults.EXPOSURE_FLOOR_PCT || v > ModulationDefaults.EXPOSURE_CEILING_PCT) {
            errors.put("policy.params." + name, "doit être dans [" + ModulationDefaults.EXPOSURE_FLOOR_PCT + " ; "
                    + ModulationDefaults.EXPOSURE_CEILING_PCT + "] %, reçu " + v);
        }
    }
}
