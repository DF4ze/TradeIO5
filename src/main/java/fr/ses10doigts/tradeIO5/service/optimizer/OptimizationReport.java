package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Rapport d'une optimisation. Reproductible à l'octet près (mêmes entrées, même graine) à l'exception du bloc {@code timings}
 * (durées mesurées) ; aucune date « courante » n'y figure.
 *
 * @param inputs       entrées explicites (espace, graine, fenêtres, capital, empreintes de bougies)
 * @param chosen       candidat retenu (centre du plus grand plateau)
 * @param chosenScore  rapport de score du candidat retenu (par fenêtre, timing pur, vetos, empreintes)
 * @param bumps        bousculades du candidat retenu
 * @param trials       nombre d'essais calculés ; {@code significanceNote} rappelle que la barre de significativité monte avec lui
 * @param vetoCounts   vetos rencontrés pendant la recherche (motif → évaluations)
 * @param timings      durées (ms) : seul bloc non reproductible
 */
public record OptimizationReport(Inputs inputs, List<SearchResult.Sensitivity> sensitivities, Chosen chosen, ScoreReport chosenScore,
                                 List<SearchResult.Bump> bumps, SearchResult.Robustness robustness, long trials,
                                 String significanceNote, Map<String, Integer> vetoCounts, List<String> vetoesMet,
                                 SearchResult.Timings timings) {

    /** Entrées explicites : rien ne dépend de l'heure ni du wallet réel. */
    public record Inputs(String label, ParamSpace space, ParamSpace searchedSpace, long seed, SearchConfig config,
                         List<WindowSpec> windows, double cash0, LocalDate endDate, String profile, double dispersionPenalty,
                         Double maxFinalStake, List<String> sources, List<CandleFingerprint> candleFingerprints) {
    }

    /** Candidat retenu et plateau (voisins à ±1 cran). */
    public record Chosen(Candidate candidate, double score, SearchResult.Plateau plateau, List<SearchResult.Finalist> finalists) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    /** Assemble le rapport à partir du résultat de recherche et des entrées du scorer. */
    public static OptimizationReport of(String label, SearchResult result, SearchConfig config, ScorerInputs base) {
        ScoreReport chosenScore = result.chosenEvaluation() != null && result.chosenEvaluation().detail() instanceof ScoreReport r ? r : null;
        List<String> sources = base.assets().stream().map(a -> a.slot().assetSymbol() + "=" + a.source()).toList();
        List<CandleFingerprint> fingerprints = chosenScore == null ? List.of() : chosenScore.fingerprint();
        Inputs inputs = new Inputs(label, result.space(), result.searchedSpace(), result.seed(), config, base.windows(), base.cash0(),
                base.endDate(), base.profile().name(), base.dispersionPenalty(), base.maxFinalStake(), sources, fingerprints);
        Chosen chosen = new Chosen(result.chosen(), result.chosenEvaluation() == null ? OptimizerDefaults.VETO_SCORE
                : result.chosenEvaluation().score(), result.plateau(), result.finalists());
        List<String> vetoesMet = result.vetoCounts().keySet().stream().sorted().toList();
        return new OptimizationReport(inputs, result.sensitivities(), chosen, chosenScore, result.bumps(), result.robustness(),
                result.trials(), OptimizerDefaults.SIGNIFICANCE_NOTE, result.vetoCounts(), vetoesMet, result.timings());
    }

    /** JSON indenté ; clés dans l'ordre des composants, candidats triés par nom. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport d'optimisation non sérialisable", e);
        }
    }

    /** JSON sans le bloc {@code timings} : sert à comparer deux rapports (reproductibilité). */
    public String toJsonWithoutTimings() {
        return new OptimizationReport(inputs, sensitivities, chosen, chosenScore, bumps, robustness, trials, significanceNote,
                vetoCounts, vetoesMet, null).toJson();
    }
}
