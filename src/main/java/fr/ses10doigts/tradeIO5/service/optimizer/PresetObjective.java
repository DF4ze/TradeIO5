package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Objectif du moteur pour UN actif : le candidat est appliqué aux jeux Bear / Bull du preset en place ({@link PresetParams#apply}),
 * score = {@link PresetScoring} (gain brut vs DCA fixe de référence, protocole V6, + veto d'exposition). Un candidat incohérent (ordre
 * des seuils ATR) est rejeté sans rejeu : {@link OptimizerDefaults#VETO_INVALID}, compté comme un essai. Sans état : thread-safe.
 */
public final class PresetObjective implements SearchObjective {

    private final ScorerInputs base;
    private final RainbowAtrParamSet[] preset;
    private final ResolvedCandidate resolved;
    private final Function<Candidate, List<String>> validator;
    private final double bandMaxPct;
    private final Map<String, ScorerInputs> worlds = new LinkedHashMap<>();

    /** @param bumpDays décalages (jours en arrière) de la date de fin des mondes bousculés */
    public PresetObjective(ScorerInputs base, RainbowAtrParamSet[] preset, ResolvedCandidate resolved,
                           Function<Candidate, List<String>> validator, double bandMaxPct, List<Integer> bumpDays) {
        this.base = base;
        this.preset = preset;
        this.resolved = resolved;
        this.validator = validator;
        this.bandMaxPct = bandMaxPct;
        for (int days : bumpDays) {
            worlds.put("fin -" + days + " j", base.withEndDate(base.endDate().minusDays(days)));
        }
    }

    @Override
    public Evaluation evaluate(Candidate candidate, Fidelity fidelity, Scenario scenario) {
        List<String> invalid = validator.apply(candidate);
        if (!invalid.isEmpty()) {
            return new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of(OptimizerDefaults.VETO_INVALID), invalid);
        }
        ScorerInputs inputs = Scenario.BASE.equals(scenario) ? base : worlds.get(scenario.label());
        if (inputs == null) {
            throw new IllegalArgumentException("Scénario inconnu : " + scenario.label());
        }
        CostMode mode = fidelity == Fidelity.ALL_WINDOWS_COMPLETE ? CostMode.COMPLETE : CostMode.SIMPLIFIED;
        ScoreReport report = PresetScoring.score(inputs, PresetParams.apply(preset, candidate), resolved, mode,
                fidelity == Fidelity.SHORT_WINDOW_SIMPLIFIED, bandMaxPct);
        return new Evaluation(report.score(), report.vetoed(), report.vetoes(), report);
    }

    @Override
    public List<Scenario> bumpScenarios() {
        return new ArrayList<>(worlds.keySet().stream().map(Scenario::new).toList());
    }
}
