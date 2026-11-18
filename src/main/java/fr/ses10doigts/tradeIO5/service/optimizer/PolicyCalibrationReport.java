package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Rapport du calibrage walk-forward de la politique L2. Reproductible à l'octet près (mêmes entrées, même graine) hors bloc
 * {@code timings} (durées mesurées) ; aucune date « courante » n'y figure. Rien n'est appliqué : c'est une recommandation.
 *
 * @param trials                    essais de recherche de TOUS les plis et profils (candidats invalides rejetés compris ; mémoïsation exclue)
 * @param evaluationsOutsideSearch  rejeux hors recherche (départ à l'entraînement, hors échantillon, mesure de pénalité)
 */
public record PolicyCalibrationReport(Inputs inputs, List<ProfileReport> profiles, List<PenaltySummary> penaltyMeasure, long trials,
                                      long evaluationsOutsideSearch, String significanceNote, List<String> notes, Timings timings) {

    /** Entrées explicites : wallet, plis, empreintes, graine, réglages. */
    public record Inputs(double cash0, int autonomyDays, List<LinkInfo> links, List<FoldInfo> folds, List<WindowSpec> trainWindows,
                         WindowSpec testWindowTemplate, long seed, SearchConfig search, double dispersionPenalty, Double maxFinalStake,
                         List<String> profiles) {
    }

    /** Lien chargé : source des bougies, multiplicateur moyen des zones et base de référence si tous les liens du wallet sont retenus. */
    public record LinkInfo(String asset, Long bindingId, String source, Double meanMultiplier, Double referenceBase) {
    }

    /** Pli : fenêtres, liens retenus / exclus (avec motif), statut {@code HISTORIQUE_INSUFFISANT} (bougies disponibles / requises), bases de référence du pli, empreintes des bougies (≤ fin d'entraînement, ≤ fin de test). */
    public record FoldInfo(LocalDate trainEnd, LocalDate testFrom, LocalDate testEnd, List<String> included, Map<String, String> excluded,
                           Map<String, Double> referenceBases, List<CandleFingerprint> trainFingerprints,
                           List<CandleFingerprint> testFingerprints, List<ScoreReport.HistoryStatus> history) {
    }

    /** Un profil : espace, départ, plis, verdict, stabilité des paramètres retenus et candidat recommandé (verdict {@code ADOPTER}). */
    public record ProfileReport(String profile, ParamSpace space, Candidate start, List<FoldResult> folds, Verdict verdict,
                                List<ParamStability> stability, Candidate recommended) {
    }

    /**
     * Résultat d'un pli (gains en % du capital vs DCA fixe de référence). {@code evaluated} faux ⇒ pli sans lien éligible ou sans fenêtre de
     * test : il ne compte pas comme gagné. {@code testVeto} : veto bloquant (drawdown du profil, capital, mise finale) du candidat retenu
     * en test ; {@code foldDecision} = {@code GARDER_DEPART} avec {@code foldReason} = {@code ENTRAINEMENT_VETOE} quand le départ ou tous les candidats
     * sont vetoés à l'entraînement (aucun candidat retenu : {@code chosen} nul, jamais un {@code INVALID_CANDIDATE}), sinon {@code RETENU} ; {@code trainVetoesStart} = vetos du point de départ à l'entraînement ; {@code
     * DEGENERATE} est rapporté mais ne bloque pas sur 12 mois de test (il se juge sur la plus longue fenêtre d'entraînement).
     */
    public record FoldResult(LocalDate trainEnd, boolean evaluated, String note, List<String> included, Candidate chosen,
                             double trainScoreChosen, boolean trainVetoed, List<String> trainVetoes, double trainScoreStart,
                             double plateauScore, int plateauSize, int plateauNeighbors, boolean robust, double worstRelativeGap,
                             List<String> frozenParams, List<SearchResult.Sensitivity> sensitivities, long trials, Map<String, Integer> vetoCounts, Double testGainChosenPct,
                             Double testGainStartPct, Double deltaVsStartPts, double testMaxDrawdownChosenPct, double testMaxDrawdownStartPct,
                             List<String> testVetoesChosen, boolean testVeto, List<String> testVetoesStart, Double pureTimingChosenPct,
                             Double pureTimingStartPct, boolean won, String foldDecision, String foldReason, List<String> trainVetoesStart) {
    }

    /** Verdict d'un profil : {@code ADOPTER} / {@code GARDER_DEPART}, motif, plis gagnés / majorité requise, médiane hors échantillon. */
    public record Verdict(String decision, List<String> reasons, int wins, int majority, int evaluatedFolds, Double medianTestGainPct,
                          boolean vetoInTest) {
    }

    /** Dispersion d'un paramètre entre les plis : valeurs retenues, étendue absolue et rapportée à la largeur de l'espace. */
    public record ParamStability(String name, List<Double> values, double min, double max, double rangeShare) {
    }

    /**
     * Mesure 4.0 B : part de la pénalité de dispersion dans le score au point de départ, par actif seul, profil et pli. Définitions : {@code
     * shareOfAbsScore = pénalité / |score brut|} (nul si score brut nul), {@code shareOfAmplitude = pénalité / (|médiane| + pénalité)} ;
     * médiane, dispersion et pénalité portent sur les seules fenêtres 12 m / 24 m (la fenêtre complète figure dans {@code windowGainsPct}, hors score).
     */
    public record PenaltyMeasure(String profile, LocalDate trainEnd, String asset, Map<String, Double> windowGainsPct, double medianPct,
                                 double dispersionPct, double penalty, double rawScore, Double shareOfAbsScore, double shareOfAmplitude) {
    }

    /** Synthèse par actif (médianes sur profils et plis) et alerte si la pénalité dépasse {@link OptimizerDefaults#PENALTY_SHARE_ALERT} du score absolu. */
    public record PenaltySummary(String asset, List<PenaltyMeasure> measures, double medianPenalty, double medianRawScore,
                                 Double medianShareOfAbsScore, double medianShareOfAmplitude, int measuresOverAlert, boolean alert,
                                 String proposal) {
    }

    /** Hors reproductibilité : durées mesurées (ms) du total et de chaque recherche. */
    public record Timings(long totalMs, List<FoldTiming> folds) {
    }

    public record FoldTiming(String profile, LocalDate trainEnd, long searchMs, long evaluationMs, double msPerRun) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    /** JSON indenté ; clés dans l'ordre des composants. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de calibrage non sérialisable", e);
        }
    }

    /** JSON sans le bloc {@code timings} : sert à comparer deux rapports (reproductibilité). */
    public String toJsonWithoutTimings() {
        return new PolicyCalibrationReport(inputs, profiles, penaltyMeasure, trials, evaluationsOutsideSearch, significanceNote, notes,
                null).toJson();
    }
}
