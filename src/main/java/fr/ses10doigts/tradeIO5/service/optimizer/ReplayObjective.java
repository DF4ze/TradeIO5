package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Objectif du moteur branché sur {@link ReplayScorer} : un {@link CandidateAdapter} traduit le candidat, le {@link Fidelity} choisit
 * le mode de coûts et les fenêtres. Mondes bousculés : date de fin décalée de n jours, source de candles alternative (si fournie).
 * Un candidat incohérent (validateur) est rejeté sans rejeu : évaluation vetoée {@link OptimizerDefaults#VETO_INVALID}, comptée comme un essai.
 * Sans état : thread-safe.
 */
public final class ReplayObjective implements SearchObjective {

    private final ScorerInputs base;
    private final CandidateAdapter adapter;
    private final Function<Candidate, List<String>> validator;
    private final Map<String, ScorerInputs> worlds = new LinkedHashMap<>();

    /**
     * @param bumpDays      décalages (jours en arrière) de la date de fin
     * @param alternatives  mondes alternatifs (étiquette → entrées, ex. autre source de candles) ; peut être vide
     */
    public ReplayObjective(ScorerInputs base, CandidateAdapter adapter, List<Integer> bumpDays, Map<String, ScorerInputs> alternatives) {
        this(base, adapter, c -> List.of(), bumpDays, alternatives);
    }

    /** @param validator motifs d'incohérence d'un candidat (vide = valide) ; vérifié avant tout rejeu */
    public ReplayObjective(ScorerInputs base, CandidateAdapter adapter, Function<Candidate, List<String>> validator, List<Integer> bumpDays,
                           Map<String, ScorerInputs> alternatives) {
        this.base = base;
        this.adapter = adapter;
        this.validator = validator;
        for (int days : bumpDays) {
            worlds.put("fin -" + days + " j", base.withEndDate(base.endDate().minusDays(days)));
        }
        alternatives.forEach((label, inputs) -> worlds.put(label, inputs));
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
        ScoreReport report = ReplayScorer.score(inputs, adapter.resolve(candidate), mode, fidelity == Fidelity.SHORT_WINDOW_SIMPLIFIED);
        return new Evaluation(report.score(), report.vetoed(), report.vetoes(), report);
    }

    @Override
    public List<Scenario> bumpScenarios() {
        return new ArrayList<>(worlds.keySet().stream().map(Scenario::new).toList());
    }
}
