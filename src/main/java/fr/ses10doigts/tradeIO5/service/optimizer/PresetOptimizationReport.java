package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Rapport JSON de l'optimiseur de presets L1 : par actif, preset en place, challenger par pli, écart de gain hors échantillon, exposition,
 * timing pur, vetos, essais ; cadences (si comparées) ; multiplicité. Rien n'est appliqué ni persisté. Mêmes entrées et même graine ⇒ JSON
 * identique à l'octet près hors {@code timings} ({@link #toJsonWithoutTimings()}).
 */
public record PresetOptimizationReport(Inputs inputs, List<AssetReport> assets, Multiplicity multiplicity, List<CadenceReport> cadences,
                                       String significanceNote, List<String> notes, Timings timings) {

    /** Entrées explicites : rien ne dépend de l'heure ni du wallet réel. */
    public record Inputs(String profile, double exposureBandMinPct, double exposureBandMaxPct, Double drawdownCapPct, double cash0,
                         int autonomyDays, long seed, SearchConfig search, List<LocalDate> foldEnds, int testMonths,
                         List<WindowSpec> trainWindows, PresetSpaceConfig space, List<String> frozenByDesign, double dispersionPenalty,
                         Double maxFinalStake) {
    }

    /**
     * Un actif : {@code OPTIMISABLE} ou {@code NON_OPTIMISABLE} (+ motif ; {@code history} = bougies disponibles / requises, jamais une
     * exclusion silencieuse). {@code inPlace} = valeurs du preset en place ; {@code params} = état de chaque paramètre (libre : bornes ;
     * gelé : raison).
     */
    public record AssetReport(String asset, String source, String status, String statusReason, List<ScoreReport.HistoryStatus> history,
                              Map<String, Double> inPlace, List<PresetParams.ParamInfo> params, List<FoldResult> folds, Verdict verdict,
                              long searchTrials, long oosReplays) {
    }

    /**
     * Un pli walk-forward : entraînement jusqu'à {@code trainEnd}, test {@code testFrom → testEnd}. {@code challenger} = valeurs retenues
     * ({@code null} si {@code GARDER_PRESET}) ; {@code deltaPts} = gain hors échantillon du challenger − celui du preset en place, en points
     * du capital ; {@code timingExcessPts} = écart de timing pur (gain à exposition égale) challenger − preset.
     */
    public record FoldResult(LocalDate trainEnd, LocalDate testFrom, LocalDate testEnd, boolean usable, String foldDecision,
                             String foldReason, String note, Map<String, Double> challenger, double trainScoreChallenger,
                             double trainScorePreset, List<String> trainVetoesChallenger, List<String> trainVetoesPreset,
                             double plateauScore, int plateauSize, int plateauNeighbors, boolean robust, double worstRelativeGap,
                             List<String> frozenBySearch, List<SearchResult.Sensitivity> sensitivities, long trials,
                             Map<String, Integer> vetoCounts, Double testGainChallengerPct, Double testGainPresetPct, Double deltaPts,
                             Double avgExposureChallengerPct, Double avgExposurePresetPct, Double pureTimingChallengerPct,
                             Double pureTimingPresetPct, Double timingExcessPts, List<String> testVetoesChallenger,
                             List<String> testVetoesPreset, boolean testVeto, boolean won, List<CandleFingerprint> trainFingerprints,
                             List<CandleFingerprint> testFingerprints) {
    }

    public record Verdict(String decision, List<String> reasons, int wins, int majority, int evaluatedFolds, int usableFolds,
                          int trainBlockedFolds, double trainBlockedShare, Double medianDeltaPts, boolean vetoInTest) {
    }

    /** Multiplicité : tout ce qui a été essayé (la barre de significativité monte avec elle). */
    public record Multiplicity(int assetsConsidered, int assetsOptimized, int searches, long searchTrials, long oosReplays,
                               List<Integer> cadencesCompared) {
    }

    /** Cadence de ré-optimisation comparée au preset statique : chaîne de challengers, gain hors échantillon cumulé. */
    public record CadenceReport(int cadenceDays, List<CadenceAsset> assets, boolean processBeatsStatic, List<String> reasons) {
    }

    /**
     * Chaîne d'un actif : à chaque ré-optimisation (fin d'entraînement {@code trainEnd}), le challenger est appliqué aux {@code cadenceDays}
     * jours suivants ; le preset statique est rejoué sur les mêmes segments. Gains en % du capital (états remis à zéro par segment pour les
     * deux bras). {@code cumulativeDeltaPts} = gain cumulé de la chaîne − gain cumulé du preset statique.
     */
    public record CadenceAsset(String asset, int segmentsPlanned, int segmentsEvaluated, int segmentsChallenger, int segmentsKeptPreset,
                               int segmentsSkipped, double trainBlockedShare, double cumulativeGainPresetPct, double cumulativeGainProcessPct,
                               double cumulativeDeltaPts, int positiveSegments, int testVetoSegments, boolean processBeatsStatic,
                               List<Segment> segments) {
    }

    public record Segment(LocalDate trainEnd, LocalDate from, LocalDate to, String decision, String reason, Double gainPresetPct,
                          Double gainChallengerPct, Double deltaPts, List<String> testVetoesChallenger) {
    }

    /** Durées mesurées : seul bloc non reproductible. */
    public record Timings(long totalMs, List<SearchTiming> searches) {
    }

    public record SearchTiming(String asset, LocalDate trainEnd, long searchMs, long trials) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport d'optimisation des presets non sérialisable", e);
        }
    }

    /** JSON sans le bloc {@code timings} : sert à comparer deux rapports (reproductibilité). */
    public String toJsonWithoutTimings() {
        return new PresetOptimizationReport(inputs, assets, multiplicity, cadences, significanceNote, notes, null).toJson();
    }
}
