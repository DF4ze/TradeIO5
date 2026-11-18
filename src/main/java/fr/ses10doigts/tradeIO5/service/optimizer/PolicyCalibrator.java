package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.FoldInfo;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.FoldResult;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.FoldTiming;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.LinkInfo;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.ParamStability;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.PenaltyMeasure;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.PenaltySummary;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.ProfileReport;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.Verdict;
import fr.ses10doigts.tradeIO5.service.optimizer.ScoreReport.WindowReport;
import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;
import fr.ses10doigts.tradeIO5.service.optimizer.WeightedStats.Point;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Calibrage walk-forward de la politique L2 sur un wallet de référence multi-actifs à cash commun. Par profil et par pli annuel :
 * recherche ({@link SearchEngine}, espace {@link L2Params}) sur des fenêtres d'entraînement finissant à T (12 m ×3, 24 m ×2, complète ×1) avec
 * des bougies TRONQUÉES à T (aucune donnée postérieure à T ne peut entrer), puis évaluation hors échantillon du candidat retenu et du point de
 * départ sur les 12 mois suivants (premier jour T + 1 : aucun chevauchement). Un lien dont l'historique est insuffisant pour les fenêtres du pli
 * est exclu du pli (et le pli le signale). Règle de décision : {@link PolicyVerdict}. Rien n'est appliqué ni persisté.
 */
@Slf4j
public final class PolicyCalibrator {

    static final String TEST_WINDOW = "test";
    static final String FOLD_RETAINED = "RETENU";
    /** Vetos qui comptent en test : le drawdown du profil, le capital et la mise finale (le veto « dégénéré » se juge sur le long terme). */
    private static final Set<String> TEST_BLOCKING_VETOES = Set.of(OptimizerDefaults.VETO_DRAWDOWN, OptimizerDefaults.VETO_CAPITAL,
            OptimizerDefaults.VETO_FINAL_STAKE);
    private static final List<String> PENALTY_ASSETS = List.of("BTC", "ETH");
    private static final double EPSILON = 1e-9;

    private final ExecutorService pool;

    public PolicyCalibrator(ExecutorService pool) {
        this.pool = pool;
    }

    /** Un pli résolu : liens retenus / exclus, entrées d'entraînement et de test (sans profil : celui du placeholder). */
    record FoldPlan(LocalDate trainEnd, LocalDate testFrom, LocalDate testEnd, List<String> included, Map<String, String> excluded,
                    List<ScoreReport.HistoryStatus> history, ScorerInputs train, ScorerInputs test) {

        boolean usable() {
            return !included.isEmpty();
        }
    }

    private record Outcome(FoldResult result, FoldTiming timing, long evaluationsOutside) {
    }

    /** @throws SearchControl.CancelledException si {@code control} est annulé */
    public PolicyCalibrationReport run(ReferenceWallet wallet, CalibrationConfig config, SearchControl control) {
        long t0 = System.nanoTime();
        List<FoldPlan> plans = config.foldEnds().stream().map(t -> plan(wallet, config, t)).toList();
        int total = config.profiles().size() * plans.size();
        int done = 0;
        long trials = 0;
        long outside = 0;
        List<ProfileReport> profiles = new ArrayList<>();
        List<FoldTiming> timings = new ArrayList<>();
        List<PenaltyMeasure> measures = new ArrayList<>();
        for (StartingProfile profile : config.profiles()) {
            List<FoldResult> folds = new ArrayList<>();
            for (FoldPlan plan : plans) {
                control.throwIfCancelled();
                int index = done++;
                Outcome o = runFold(config, profile, plan, control, index, total);
                folds.add(o.result());
                if (o.timing() != null) {
                    timings.add(o.timing());
                }
                trials += o.result().trials();
                outside += o.evaluationsOutside();
                if (plan.usable()) {
                    outside += measure(wallet, config, profile, plan, measures);
                }
            }
            profiles.add(profileReport(profile, folds, config));
        }
        control.progress(1.0, "terminé");
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("Calibrage terminé : {} profils × {} plis, {} essais, {} ms", config.profiles().size(), plans.size(), trials, totalMs);
        return new PolicyCalibrationReport(inputs(wallet, config, plans), profiles, penaltySummaries(measures), trials,
                outside, OptimizerDefaults.SIGNIFICANCE_NOTE, notes(), new PolicyCalibrationReport.Timings(totalMs, timings));
    }

    private static List<String> notes() {
        return List.of("Gain = (valeur finale − valeur finale du DCA fixe de référence) / capital, en %. Le DCA fixe est calculé au montant de base de "
                        + "référence (proposition d'onboarding), identique pour tous les candidats d'un pli.",
                "Candidat recommandé (verdict ADOPTER) = candidat retenu du pli le plus récent ; la stabilité des paramètres d'un pli à l'autre est "
                        + "rapportée par profil. Rien n'est appliqué.");
    }

    // ------------------------------------------------------------------ plis

    FoldPlan plan(ReferenceWallet wallet, CalibrationConfig config, LocalDate trainEnd) {
        ScorerInputs all = wallet.inputs();
        LocalDate testEnd = config.testEnd(trainEnd);
        LocalDate testFrom = trainEnd.plusDays(1);
        List<WindowSpec> trainWindows = WindowSpec.defaults();
        List<WindowSpec> testWindows = List.of(new WindowSpec(TEST_WINDOW, config.testMonths(), 1, testFrom));
        Map<String, String> excluded = new LinkedHashMap<>();
        ReplayScorer.ineligibleLinks(truncated(all, trainEnd).withWindows(trainWindows), trainWindows).forEach(excluded::putIfAbsent);
        ReplayScorer.ineligibleLinks(truncated(all, testEnd).withWindows(testWindows), testWindows).forEach(excluded::putIfAbsent);
        List<ScoreReport.HistoryStatus> history = new ArrayList<>(ReplayScorer.historyStatuses(truncated(all, trainEnd).withWindows(trainWindows), trainWindows));
        ReplayScorer.historyStatuses(truncated(all, testEnd).withWindows(testWindows), testWindows).stream()
                .filter(h -> history.stream().noneMatch(x -> x.asset().equals(h.asset()))).forEach(history::add);
        List<String> included = all.assets().stream().map(a -> a.slot().assetSymbol()).filter(a -> !excluded.containsKey(a)).toList();
        if (included.isEmpty()) {
            return new FoldPlan(trainEnd, testFrom, testEnd, included, excluded, history, null, null);
        }
        ScorerInputs selected = wallet.select(included);
        return new FoldPlan(trainEnd, testFrom, testEnd, included, excluded, history, truncated(selected, trainEnd).withWindows(trainWindows),
                truncated(selected, testEnd).withWindows(testWindows));
    }

    /** Entrées dont les bougies de chaque lien sont coupées au jour {@code day} (indicateurs et régimes recalculés sans le futur). */
    static ScorerInputs truncated(ScorerInputs in, LocalDate day) {
        return in.withEndDate(day).withAssets(in.assets().stream().map(a -> truncated(a, day)).toList());
    }

    private static AssetInput truncated(AssetInput a, LocalDate day) {
        LinkSignalReplay.Config t = a.template();
        List<MarketData> kept = t.candles().stream().filter(c -> !c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isAfter(day)).toList();
        if (kept.size() == t.candles().size()) {
            return a;
        }
        return a.withTemplate(new LinkSignalReplay.Config(RainbowAtrDataset.fromMarketData(kept), kept, t.trend(), t.range(), t.sets(),
                t.engineStart(), t.startIdx(), Math.min(t.endIdx(), kept.size() - 1), t.linkBaseAmount(), t.priceOverride(), t.athBefore(),
                t.rebootstrapDays(), t.windowMonths(), t.athCap(), t.fixedBaseAmount()));
    }

    // ------------------------------------------------------------------ un profil sur un pli

    private Outcome runFold(CalibrationConfig config, StartingProfile profile, FoldPlan plan, SearchControl control, int index, int total) {
        ParamSpace space = L2Params.space(profile, config.startOverrides());
        Candidate start = space.startCandidate();
        if (!plan.usable()) {
            return new Outcome(skipped(plan, "aucun lien éligible : " + plan.excluded()), null, 0);
        }
        ScorerInputs train = plan.train().withProfile(config.trainDrawdownVeto() ? profile : StartingProfile.CUSTOM); // CUSTOM : aucun plafond
        ScorerInputs test = plan.test().withProfile(profile);
        CandidateAdapter adapter = L2Params.adapter(profile);
        ReplayObjective objective = new ReplayObjective(train, adapter, L2Params.validator(train.limits()), OptimizerDefaults.BUMP_DAYS, Map.of());
        String label = "profil " + profile + " pli " + plan.trainEnd();
        SearchControl child = control.child((fraction, stage) -> control.progress((index + fraction) / total, label + " : " + stage));
        SearchResult result = new SearchEngine(pool).run(space, objective, config.search(), child);
        Candidate chosen = result.chosen();
        ScoreReport trainStart = ReplayScorer.score(train, adapter.resolve(start), CostMode.COMPLETE, false);
        ScoreReport testStart = ReplayScorer.score(test, adapter.resolve(start), CostMode.COMPLETE, false);
        FoldTiming timing = new FoldTiming(profile.name(), plan.trainEnd(), result.timings().totalMs(), result.timings().evaluationMs(),
                result.timings().msPerRun());
        if (trainBlocked(result.chosenEvaluation(), trainStart, config.dropFoldOnStartVeto())) { // rien de retenable : le pli garde le départ
            return new Outcome(blockedFold(plan, result, trainStart, testStart), timing, 2);
        }
        ScoreReport testChosen = ReplayScorer.score(test, adapter.resolve(chosen), CostMode.COMPLETE, false);
        return new Outcome(foldResult(plan, result, trainStart, testChosen, testStart), timing, 3);
    }

    /**
     * Entraînement inexploitable : le candidat retenu est vetoé (donc tous les candidats le sont, dont un {@code INVALID_CANDIDATE}) ou le
     * point de départ l'est. Le pli donne {@code GARDER_DEPART} ({@link OptimizerDefaults#FOLD_TRAIN_VETOED}) : jamais de candidat retenu.
     */
    static boolean trainBlocked(Evaluation chosenEvaluation, ScoreReport trainStart) {
        return trainBlocked(chosenEvaluation, trainStart, true);
    }

    static boolean trainBlocked(Evaluation chosenEvaluation, ScoreReport trainStart, boolean dropOnStartVeto) {
        return chosenEvaluation.vetoed() || chosenEvaluation.vetoes().contains(OptimizerDefaults.VETO_INVALID)
                || dropOnStartVeto && trainStart.vetoed();
    }

    /** Pli {@code GARDER_DEPART} / {@code ENTRAINEMENT_VETOE} : aucun candidat retenu ni évalué en test ; le départ est rapporté en test. */
    private static FoldResult blockedFold(FoldPlan plan, SearchResult result, ScoreReport trainStart, ScoreReport testStart) {
        boolean evaluated = !testStart.windows().isEmpty() && testStart.medianGainPct() != null;
        List<String> frozen = result.sensitivities().stream().filter(SearchResult.Sensitivity::frozen).map(SearchResult.Sensitivity::name).toList();
        return new FoldResult(plan.trainEnd(), evaluated, result.chosenEvaluation().vetoed()
                ? "tous les candidats vetoés à l'entraînement : " + result.chosenEvaluation().vetoes()
                : "départ vetoé à l'entraînement : " + trainStart.vetoes(),
                plan.included(), null, result.chosenEvaluation().score(), true, result.chosenEvaluation().vetoes(), trainStart.score(), 0, 0, 0, false,
                0, frozen, result.sensitivities(), result.trials(), result.vetoCounts(), null, evaluated ? testStart.medianGainPct() : null, null, 0,
                drawdown(testStart), List.of(), false, testStart.vetoes(), null,
                testStart.pureTiming() == null ? null : testStart.pureTiming().weightedMedianPct(), false, OptimizerDefaults.VERDICT_KEEP_START,
                OptimizerDefaults.FOLD_TRAIN_VETOED, trainStart.vetoes());
    }

    private static FoldResult foldResult(FoldPlan plan, SearchResult result, ScoreReport trainStart, ScoreReport testChosen,
                                         ScoreReport testStart) {
        Evaluation chosenEval = result.chosenEvaluation();
        boolean trainVetoed = chosenEval.vetoed();
        boolean evaluated = testChosen.medianGainPct() != null && testStart.medianGainPct() != null;
        List<String> frozen = result.sensitivities().stream().filter(SearchResult.Sensitivity::frozen).map(SearchResult.Sensitivity::name).toList();
        Double gainChosen = evaluated ? testChosen.medianGainPct() : null;
        Double gainStart = evaluated ? testStart.medianGainPct() : null;
        Double delta = evaluated ? gainChosen - gainStart : null;
        boolean testVeto = evaluated && testChosen.vetoes().stream().anyMatch(TEST_BLOCKING_VETOES::contains);
        boolean won = evaluated && !trainVetoed && !testVeto && delta > 0;
        String note = evaluated ? null : "aucune fenêtre de test rejouable : " + testChosen.excluded();
        return new FoldResult(plan.trainEnd(), evaluated, note, plan.included(), result.chosen(), chosenEval.score(), trainVetoed,
                chosenEval.vetoes(), trainStart.score(), result.plateau().plateauScore(), result.plateau().size(), result.plateau().neighbors(),
                result.robustness().robust(), result.robustness().worstRelativeGap(), frozen, result.sensitivities(), result.trials(), result.vetoCounts(), gainChosen,
                gainStart, delta, drawdown(testChosen), drawdown(testStart), testChosen.vetoes(), testVeto, testStart.vetoes(),
                testChosen.pureTiming() == null ? null : testChosen.pureTiming().weightedMedianPct(),
                testStart.pureTiming() == null ? null : testStart.pureTiming().weightedMedianPct(), won, FOLD_RETAINED, null, trainStart.vetoes());
    }

    private static double drawdown(ScoreReport r) {
        return r.windows().stream().mapToDouble(WindowReport::maxDrawdownPct).min().orElse(0.0);
    }

    private static FoldResult skipped(FoldPlan plan, String note) {
        return new FoldResult(plan.trainEnd(), false, note, plan.included(), null, OptimizerDefaults.VETO_SCORE, false, List.of(),
                OptimizerDefaults.VETO_SCORE, 0, 0, 0, false, 0, List.of(), List.of(), 0, Map.of(), null, null, null, 0, 0, List.of(), false, List.of(),
                null, null, false, OptimizerDefaults.VERDICT_KEEP_START, null, List.of());
    }

    // ------------------------------------------------------------------ verdict et stabilité

    private static ProfileReport profileReport(StartingProfile profile, List<FoldResult> folds, CalibrationConfig config) {
        ParamSpace space = L2Params.space(profile, config.startOverrides());
        Verdict verdict = PolicyVerdict.decide(folds.stream()
                .map(f -> new PolicyVerdict.FoldScore(f.evaluated(), f.won(), f.testVeto(), f.testGainChosenPct())).toList(), config.foldEnds().size());
        List<FoldResult> withCandidate = folds.stream().filter(f -> f.chosen() != null).toList();
        Candidate recommended = OptimizerDefaults.VERDICT_ADOPT.equals(verdict.decision()) && !withCandidate.isEmpty()
                ? withCandidate.stream().filter(FoldResult::evaluated).reduce((a, b) -> b).orElse(withCandidate.getLast()).chosen() : null;
        long blocked = folds.stream().filter(f -> OptimizerDefaults.FOLD_TRAIN_VETOED.equals(f.foldReason())).count();
        if (blocked > 0) {
            List<String> reasons = new ArrayList<>(verdict.reasons());
            reasons.add(blocked + "/" + folds.size() + " plis GARDER_DEPART (" + OptimizerDefaults.FOLD_TRAIN_VETOED + ")");
            verdict = new Verdict(verdict.decision(), List.copyOf(reasons), verdict.wins(), verdict.majority(), verdict.evaluatedFolds(),
                    verdict.medianTestGainPct(), verdict.vetoInTest());
        }
        return new ProfileReport(profile.name(), space, space.startCandidate(), folds, verdict, stability(space, withCandidate), recommended);
    }

    private static List<ParamStability> stability(ParamSpace space, List<FoldResult> folds) {
        List<ParamStability> out = new ArrayList<>();
        if (folds.isEmpty()) {
            return out;
        }
        for (ParamSpace.Param p : space.params()) {
            List<Double> values = folds.stream().map(f -> f.chosen().get(p.name())).toList();
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            out.add(new ParamStability(p.name(), values, min, max, (max - min) / (p.max() - p.min())));
        }
        return out;
    }

    // ------------------------------------------------------------------ mesure 4.0 B : part de la pénalité de dispersion

    /** Mesure, au point de départ, chaque actif BTC / ETH seul (cash 100 % à lui) sur les fenêtres d'entraînement du pli ; renvoie le nombre de rejeux. */
    private long measure(ReferenceWallet wallet, CalibrationConfig config, StartingProfile profile, FoldPlan plan, List<PenaltyMeasure> out) {
        long runs = 0;
        ResolvedCandidate start = L2Params.adapter(profile).resolve(L2Params.space(profile, config.startOverrides()).startCandidate());
        for (String asset : PENALTY_ASSETS) {
            if (!plan.included().contains(asset)) {
                continue;
            }
            ScorerInputs single = truncated(wallet.select(List.of(asset)), plan.trainEnd()).withWindows(WindowSpec.defaults()).withProfile(profile);
            ScoreReport r = ReplayScorer.score(single, start, CostMode.COMPLETE, false);
            runs++;
            if (!r.windows().isEmpty()) {
                out.add(penaltyMeasure(profile, plan.trainEnd(), asset, r));
            }
        }
        return runs;
    }

    static PenaltyMeasure penaltyMeasure(StartingProfile profile, LocalDate trainEnd, String asset, ScoreReport r) {
        Map<String, Double> gains = new LinkedHashMap<>();
        r.windows().forEach(w -> gains.put(w.name(), w.gainPct()));
        double median = r.medianGainPct();
        double penalty = r.penalty();
        double raw = r.rawScore();
        Double share = Math.abs(raw) > EPSILON ? penalty / Math.abs(raw) : null;
        double amplitude = Math.abs(median) + penalty;
        return new PenaltyMeasure(profile.name(), trainEnd, asset, gains, median, r.dispersionPct(), penalty, raw, share,
                amplitude > EPSILON ? penalty / amplitude : 0.0);
    }

    static List<PenaltySummary> penaltySummaries(List<PenaltyMeasure> measures) {
        List<PenaltySummary> out = new ArrayList<>();
        for (String asset : PENALTY_ASSETS) {
            List<PenaltyMeasure> mine = measures.stream().filter(m -> m.asset().equals(asset)).toList();
            if (mine.isEmpty()) {
                continue;
            }
            Double medianShare = medianOf(mine.stream().map(PenaltyMeasure::shareOfAbsScore).filter(x -> x != null).toList());
            double medianPenalty = medianOf(mine.stream().map(PenaltyMeasure::penalty).toList());
            boolean alert = medianShare != null && medianShare > OptimizerDefaults.PENALTY_SHARE_ALERT;
            int over = (int) mine.stream().filter(m -> m.shareOfAbsScore() != null && m.shareOfAbsScore() > OptimizerDefaults.PENALTY_SHARE_ALERT).count();
            String proposal = alert ? String.format(Locale.ROOT, "ALERTE : pénalité médiane %.2f pt = %s du score absolu (> %.0f %%) sur 12 m / 24 m.",
                    medianPenalty, fmtPct(medianShare), 100 * OptimizerDefaults.PENALTY_SHARE_ALERT)
                    : String.format(Locale.ROOT, "Sous le seuil (%s du score absolu, seuil %.0f %%) : formule inchangée.", fmtPct(medianShare),
                    100 * OptimizerDefaults.PENALTY_SHARE_ALERT);
            out.add(new PenaltySummary(asset, mine, medianPenalty, medianOf(mine.stream().map(PenaltyMeasure::rawScore).toList()), medianShare,
                    medianOf(mine.stream().map(PenaltyMeasure::shareOfAmplitude).toList()), over, alert, proposal));
        }
        return out;
    }

    private static double medianOf(List<Double> values) {
        return WeightedStats.median(values.stream().map(v -> new Point(v, 1)).toList());
    }

    private static String fmtPct(Double v) {
        return v == null ? "n/a" : String.format(Locale.ROOT, "%.0f %%", 100 * v);
    }

    // ------------------------------------------------------------------ entrées du rapport

    private static PolicyCalibrationReport.Inputs inputs(ReferenceWallet wallet, CalibrationConfig config, List<FoldPlan> plans) {
        ScorerInputs in = wallet.inputs();
        List<LinkInfo> links = in.assets().stream().map(a -> new LinkInfo(a.slot().assetSymbol(), a.slot().bindingId(), a.source(),
                a.meanMultiplier(), a.referenceBase())).toList();
        List<FoldInfo> folds = plans.stream().map(PolicyCalibrator::foldInfo).toList();
        return new PolicyCalibrationReport.Inputs(in.cash0(), wallet.autonomyDays(), links, folds, WindowSpec.defaults(),
                new WindowSpec(TEST_WINDOW, config.testMonths(), 1, null), config.search().seed(), config.search(), in.dispersionPenalty(),
                in.maxFinalStake(), config.profiles().stream().map(Enum::name).toList());
    }

    private static FoldInfo foldInfo(FoldPlan p) {
        if (!p.usable()) {
            return new FoldInfo(p.trainEnd(), p.testFrom(), p.testEnd(), p.included(), p.excluded(), Map.of(), List.of(), List.of(), p.history());
        }
        Map<String, Double> bases = new LinkedHashMap<>();
        p.train().assets().forEach(a -> bases.put(a.slot().assetSymbol(), a.referenceBase()));
        return new FoldInfo(p.trainEnd(), p.testFrom(), p.testEnd(), p.included(), p.excluded(), bases, fingerprints(p.train(), p.trainEnd()),
                fingerprints(p.test(), p.testEnd()), p.history());
    }

    private static List<CandleFingerprint> fingerprints(ScorerInputs in, LocalDate end) {
        return in.assets().stream().map(a -> CandleFingerprint.of(a.slot().assetSymbol(), a.template().candles(), end)).toList();
    }
}
