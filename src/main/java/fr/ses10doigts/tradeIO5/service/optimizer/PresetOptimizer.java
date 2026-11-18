package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.AssetReport;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.CadenceAsset;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.CadenceReport;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.FoldResult;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.Multiplicity;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.SearchTiming;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.Segment;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.Verdict;
import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * Optimiseur de presets L1, par actif : cherche le preset Rainbow Bull / Bear qui gagne le plus HORS ÉCHANTILLON, à partir du preset en
 * place (point de départ ET comparateur). CHALLENGER seulement : rien n'est appliqué ni persisté, le live ne change pas.
 * <ul>
 *   <li><b>Espace</b> : {@link PresetSpaceConfig} (libres / gelés, δ) lu depuis une {@link PresetSpaceSource} ; Trend, ATH, Moon,
 *   multiplicateurs, montant de base figés ({@link PresetParam#FROZEN_BY_DESIGN}).</li>
 *   <li><b>Évaluation</b> : {@link ReplayScorer} via {@link PresetScoring} (fenêtres 12 m / 24 m, gain vs DCA fixe de référence 360 j fixée a
 *   priori, protocole V6) + veto d'exposition ; un actif à la fois, cash entier sur lui.</li>
 *   <li><b>Recherche</b> : {@link SearchEngine} (Sobol + halving + plateau + bousculades), pool dédié, un {@link PresetObjective} par actif.</li>
 *   <li><b>Garde-fous</b> : historique insuffisant ⇒ {@code NON_OPTIMISABLE} (bougies disponibles / requises) ; entraînement ou départ vetoé
 *   ⇒ {@code GARDER_PRESET}, jamais un {@code INVALID_CANDIDATE} retenu.</li>
 *   <li><b>Walk-forward simple</b> ({@link #run}) et <b>cadences</b> de ré-optimisation ({@link #runWithCadences}) comparées au preset statique.</li>
 * </ul>
 */
@Slf4j
public final class PresetOptimizer {

    static final String TEST_WINDOW = "test";
    static final String FOLD_CHALLENGER = "CHALLENGER";
    private static final String FOLD_KEEP = OptimizerDefaults.VERDICT_KEEP_PRESET;
    /** Vetos qui comptent en test : drawdown du profil, capital, mise finale et bande d'exposition (le « dégénéré » se juge sur le long terme). */
    private static final Set<String> TEST_BLOCKING_VETOES = Set.of(OptimizerDefaults.VETO_DRAWDOWN, OptimizerDefaults.VETO_CAPITAL,
            OptimizerDefaults.VETO_FINAL_STAKE, OptimizerDefaults.VETO_EXPOSURE);
    /** Marqueur « borné » d'une fenêtre de segment : seul le premier jour ({@code from}) et la date de fin fixent la fenêtre. */
    private static final int SEGMENT_MARKER_MONTHS = 1;
    private static final int REPLAYS_PER_EVALUATED_SEARCH = 3;

    private final ExecutorService pool;
    private final PresetSpaceSource source;

    public PresetOptimizer(ExecutorService pool, PresetSpaceSource source) {
        this.pool = pool;
        this.source = source;
    }

    /** Walk-forward simple (une graine, un run, aucune variante). @throws SearchControl.CancelledException si {@code control} est annulé */
    public PresetOptimizationReport run(ReferenceWallet wallet, PresetOptimizerConfig config, SearchControl control) {
        return execute(wallet, config, control, false);
    }

    /** Walk-forward simple puis comparaison des cadences de ré-optimisation (coût élevé : à lancer après estimation et accord). */
    public PresetOptimizationReport runWithCadences(ReferenceWallet wallet, PresetOptimizerConfig config, SearchControl control) {
        return execute(wallet, config, control, true);
    }

    // ------------------------------------------------------------------ structures internes

    /** Un actif : entrées (cash entier sur lui, bande du profil), preset en place, espace, candidat résolu. */
    private record Ctx(String asset, String sourceLabel, ScorerInputs full, RainbowAtrParamSet[] preset, PresetParams.Built built,
                       ResolvedCandidate resolved, Function<Candidate, List<String>> validator, double bandMaxPct) {
    }

    /** Un entraînement / test : entrées coupées aux bonnes dates, éligibilité et statuts d'historique. */
    private record Plan(LocalDate trainEnd, LocalDate testFrom, LocalDate testEnd, ScorerInputs train, ScorerInputs test,
                        Map<String, String> ineligible, List<ScoreReport.HistoryStatus> history) {
        boolean usable() {
            return ineligible.isEmpty();
        }
    }

    private record Outcome(SearchResult result, ScoreReport trainStart, boolean blocked) {
    }

    private static final class Counters {
        long searches;
        long trials;
        long oosReplays;
    }

    private static final class Progress {
        final SearchControl control;
        final int total;
        int done;

        Progress(SearchControl control, int total) {
            this.control = control;
            this.total = Math.max(1, total);
        }

        void step(String stage) {
            done++;
            control.progress(Math.min(0.99, (double) done / total), stage);
        }
    }

    // ------------------------------------------------------------------ exécution

    private PresetOptimizationReport execute(ReferenceWallet wallet, PresetOptimizerConfig cfg, SearchControl control, boolean cadences) {
        long t0 = System.nanoTime();
        PresetSpaceConfig space = source.current();
        ReferenceWallet fixed = new ReferenceWallet(wallet.inputs(), cfg.autonomyDays());
        List<Ctx> contexts = fixed.inputs().assets().stream().map(a -> context(fixed, a, cfg, space)).toList();
        LocalDate end = fixed.inputs().endDate();
        int total = contexts.size() * cfg.foldEnds().size();
        if (cadences) {
            for (int days : cfg.cadenceDays()) {
                total += contexts.size() * segmentCount(cfg.cadenceStart(), end, days);
            }
        }
        Progress progress = new Progress(control, total);
        Counters counters = new Counters();
        List<SearchTiming> timings = new ArrayList<>();
        Map<String, Map<LocalDate, Outcome>> memo = new HashMap<>();
        List<AssetReport> assets = new ArrayList<>();
        for (Ctx ctx : contexts) {
            assets.add(walkForward(ctx, cfg, control, progress, memo.computeIfAbsent(ctx.asset(), k -> new HashMap<>()), counters, timings));
        }
        List<CadenceReport> chains = new ArrayList<>();
        if (cadences) {
            for (int days : cfg.cadenceDays()) {
                List<CadenceAsset> perAsset = new ArrayList<>();
                for (Ctx ctx : contexts) {
                    perAsset.add(chain(ctx, days, cfg, end, control, progress, memo.get(ctx.asset()), counters, timings));
                }
                chains.add(cadenceReport(days, perAsset));
            }
        }
        control.progress(1.0, "terminé");
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        long optimized = assets.stream().filter(a -> OptimizerDefaults.STATUS_OPTIMIZABLE.equals(a.status())).count();
        log.info("Presets L1 terminés : {} actifs ({} optimisables), {} recherches, {} essais, {} ms", assets.size(), optimized,
                counters.searches, counters.trials, totalMs);
        return new PresetOptimizationReport(inputs(contexts.getFirst(), fixed, cfg, space), assets,
                new Multiplicity(assets.size(), (int) optimized, (int) counters.searches, counters.trials, counters.oosReplays,
                        cadences ? cfg.cadenceDays() : List.of()),
                chains, OptimizerDefaults.SIGNIFICANCE_NOTE, notes(), new PresetOptimizationReport.Timings(totalMs, timings));
    }

    private static List<String> notes() {
        return List.of("Rien n'est appliqué ni persisté : le preset en place est le point de départ et le comparateur, le challenger n'est qu'un "
                        + "candidat.",
                "Gain = (valeur finale − valeur finale du DCA fixe de référence) / capital, en %. Référence fixée a priori (autonomie "
                        + CalibrationConfig.V6_REFERENCE_AUTONOMY_DAYS + " j, protocole V6), identique pour le preset en place et tous les "
                        + "challengers ; jamais ajustée pour obtenir un gain.",
                "Un actif à la fois (cash entier sur lui). Montant de base figé (multiple 1 de la base de référence du lien) ; Trend figée ; bande "
                        + "d'exposition constante du profil et plafond ATH par défaut du profil, identiques pour tous les candidats.",
                "Paramètres libres par jeu Bear / Bull (noms bear.* / bull.*) ; un paramètre de valeur nulle ou sans marge reste gelé (raison dans "
                        + "params).",
                "Veto d'exposition : exposition moyenne d'une fenêtre notée > borne haute de la bande du profil. Timing pur = gain à exposition "
                        + "égale (hors score).",
                "Cadences : chaque segment est rejoué à états remis à zéro (ni position ni cash reportés) pour les deux bras, preset statique et "
                        + "chaîne de challengers, sur les mêmes jours.");
    }

    private static PresetOptimizationReport.Inputs inputs(Ctx first, ReferenceWallet wallet, PresetOptimizerConfig cfg, PresetSpaceConfig space) {
        ScorerInputs in = first.full();
        StartingProfile profile = cfg.profile();
        java.math.BigDecimal cap = in.limits().drawdownCaps().get(profile);
        return new PresetOptimizationReport.Inputs(profile.name(), profile.exposureMin(), profile.exposureMax(),
                cap == null ? null : cap.doubleValue(), in.cash0(), wallet.autonomyDays(), cfg.search().seed(), cfg.search(), cfg.foldEnds(),
                cfg.testMonths(), WindowSpec.defaults(), space, PresetParam.FROZEN_BY_DESIGN, in.dispersionPenalty(), in.maxFinalStake());
    }

    private Ctx context(ReferenceWallet wallet, AssetInput link, PresetOptimizerConfig cfg, PresetSpaceConfig space) {
        String asset = link.slot().assetSymbol();
        StartingProfile profile = cfg.profile();
        ModulationSettings band = new ModulationSettings(profile.exposureMin(), profile.exposureMax(), ModulationDefaults.NEUTRAL_POWER,
                ModulationDefaults.NEUTRAL_BIAS);
        ScorerInputs full = wallet.select(List.of(asset)).withProfile(profile).withBaseSettings(band);
        RainbowAtrParamSet[] preset = full.assets().getFirst().template().sets();
        return new Ctx(asset, link.source(), full, preset, PresetParams.build(preset, space),
                new ResolvedCandidate(null, ModulationDefaults.athCapOf(profile), 1.0), PresetParams.validator(preset), profile.exposureMax());
    }

    // ------------------------------------------------------------------ plis et recherche

    /** Entraînement coupé à {@code trainEnd} (fenêtres 12 m / 24 m / complète), test {@code trainEnd + 1 → testEnd} ; aucun futur n'entre. */
    private Plan plan(Ctx ctx, LocalDate trainEnd, LocalDate testEnd, int testMonths) {
        List<WindowSpec> trainWindows = WindowSpec.defaults();
        List<WindowSpec> testWindows = List.of(new WindowSpec(TEST_WINDOW, testMonths, 1, trainEnd.plusDays(1)));
        ScorerInputs train = PolicyCalibrator.truncated(ctx.full(), trainEnd).withWindows(trainWindows);
        ScorerInputs test = PolicyCalibrator.truncated(ctx.full(), testEnd).withWindows(testWindows);
        Map<String, String> ineligible = new LinkedHashMap<>();
        ReplayScorer.ineligibleLinks(train, trainWindows).forEach(ineligible::putIfAbsent);
        ReplayScorer.ineligibleLinks(test, testWindows).forEach(ineligible::putIfAbsent);
        List<ScoreReport.HistoryStatus> history = new ArrayList<>(ReplayScorer.historyStatuses(train, trainWindows));
        ReplayScorer.historyStatuses(test, testWindows).stream().filter(h -> history.stream().noneMatch(x -> x.asset().equals(h.asset())))
                .forEach(history::add);
        return new Plan(trainEnd, trainEnd.plusDays(1), testEnd, train, test, ineligible, history);
    }

    /** Recherche d'un entraînement (mémoïsée par fin d'entraînement : une cadence en réutilise une autre). */
    private Outcome outcome(Ctx ctx, Plan plan, PresetOptimizerConfig cfg, SearchControl control, Progress progress,
                            Map<LocalDate, Outcome> memo, Counters counters, List<SearchTiming> timings) {
        Outcome cached = memo.get(plan.trainEnd());
        if (cached != null) {
            return cached;
        }
        PresetObjective objective = new PresetObjective(plan.train(), ctx.preset(), ctx.resolved(), ctx.validator(), ctx.bandMaxPct(),
                OptimizerDefaults.BUMP_DAYS);
        String label = ctx.asset() + " " + plan.trainEnd();
        SearchControl child = control.child((fraction, stage) -> control.progress(Math.min(0.99, (progress.done + fraction) / progress.total),
                label + " : " + stage));
        long t0 = System.nanoTime();
        SearchResult result = new SearchEngine(pool).run(ctx.built().space(), objective, cfg.search(), child);
        ScoreReport trainStart = PresetScoring.score(plan.train(), ctx.preset(), ctx.resolved(), CostMode.COMPLETE, false, ctx.bandMaxPct());
        boolean blocked = PolicyCalibrator.trainBlocked(result.chosenEvaluation(), trainStart, true);
        counters.searches++;
        counters.trials += result.trials();
        counters.oosReplays++;
        timings.add(new SearchTiming(ctx.asset(), plan.trainEnd(), (System.nanoTime() - t0) / 1_000_000L, result.trials()));
        Outcome outcome = new Outcome(result, trainStart, blocked);
        memo.put(plan.trainEnd(), outcome);
        return outcome;
    }

    private ScoreReport replayTest(Ctx ctx, Plan plan, RainbowAtrParamSet[] sets, Counters counters) {
        counters.oosReplays++;
        return PresetScoring.score(plan.test(), sets, ctx.resolved(), CostMode.COMPLETE, false, ctx.bandMaxPct());
    }

    // ------------------------------------------------------------------ walk-forward simple

    private AssetReport walkForward(Ctx ctx, PresetOptimizerConfig cfg, SearchControl control, Progress progress, Map<LocalDate, Outcome> memo,
                                    Counters counters, List<SearchTiming> timings) {
        List<Plan> plans = cfg.foldEnds().stream().map(t -> plan(ctx, t, cfg.testEnd(t), cfg.testMonths())).toList();
        boolean anyUsable = plans.stream().anyMatch(Plan::usable);
        boolean noFree = ctx.built().space().free().isEmpty();
        List<FoldResult> folds = new ArrayList<>();
        long trials = 0;
        long replays = 0;
        for (Plan plan : plans) {
            control.throwIfCancelled();
            if (!plan.usable() || noFree) {
                folds.add(unusable(plan, noFree && plan.usable() ? OptimizerDefaults.REASON_NO_FREE_PARAM : OptimizerDefaults.STATUS_INSUFFICIENT_HISTORY));
                progress.step(ctx.asset() + " " + plan.trainEnd() + " : non exploitable");
                continue;
            }
            long before = counters.oosReplays;
            Outcome outcome = outcome(ctx, plan, cfg, control, progress, memo, counters, timings);
            FoldResult fold = foldResult(ctx, plan, outcome, counters);
            folds.add(fold);
            trials += fold.trials();
            replays += counters.oosReplays - before;
            progress.step(ctx.asset() + " " + plan.trainEnd() + " : pli terminé");
        }
        List<FoldResult> usable = folds.stream().filter(FoldResult::usable).toList();
        String status = !anyUsable || noFree ? OptimizerDefaults.STATUS_NOT_OPTIMIZABLE : OptimizerDefaults.STATUS_OPTIMIZABLE;
        String reason = !anyUsable ? OptimizerDefaults.STATUS_INSUFFICIENT_HISTORY : noFree ? OptimizerDefaults.REASON_NO_FREE_PARAM : null;
        Verdict verdict = status.equals(OptimizerDefaults.STATUS_OPTIMIZABLE)
                ? PresetVerdict.decide(usable.stream().map(PresetOptimizer::foldScore).toList(), usable.size()) : notOptimizable(reason, plans.size());
        return new AssetReport(ctx.asset(), ctx.sourceLabel(), status, reason, anyUsable ? List.of() : plans.getLast().history(),
                PresetParams.values(ctx.preset()), ctx.built().info(), folds, verdict, trials, replays);
    }

    /** Ce que le verdict lit d'un pli : un pli à entraînement vetoé garde le preset (écart nul) ; sinon l'écart hors échantillon mesuré. */
    private static PresetVerdict.FoldScore foldScore(FoldResult f) {
        boolean blocked = OptimizerDefaults.FOLD_TRAIN_VETOED.equals(f.foldReason());
        boolean evaluated = blocked ? f.testGainPresetPct() != null : f.deltaPts() != null;
        return new PresetVerdict.FoldScore(evaluated, f.won(), f.testVeto(), blocked, blocked ? (evaluated ? 0.0 : null) : f.deltaPts());
    }

    private static Verdict notOptimizable(String reason, int plans) {
        return new Verdict(OptimizerDefaults.VERDICT_KEEP_PRESET, List.of(reason), 0, plans / 2 + 1, 0, 0, 0, 0.0, null, false);
    }

    private static FoldResult unusable(Plan plan, String reason) {
        return new FoldResult(plan.trainEnd(), plan.testFrom(), plan.testEnd(), false, FOLD_KEEP, reason,
                plan.usable() ? null : plan.ineligible().toString(), null, OptimizerDefaults.VETO_SCORE, OptimizerDefaults.VETO_SCORE, List.of(),
                List.of(), 0, 0, 0, false, 0, List.of(), List.of(), 0, Map.of(), null, null, null, null, null, null, null, null, List.of(),
                List.of(), false, false, List.of(), List.of());
    }

    private FoldResult foldResult(Ctx ctx, Plan plan, Outcome o, Counters counters) {
        SearchResult r = o.result();
        Evaluation chosenEval = r.chosenEvaluation();
        List<String> frozen = r.sensitivities().stream().filter(SearchResult.Sensitivity::frozen).map(SearchResult.Sensitivity::name).toList();
        List<CandleFingerprint> trainFp = fingerprints(plan.train(), plan.trainEnd());
        List<CandleFingerprint> testFp = fingerprints(plan.test(), plan.testEnd());
        ScoreReport testPreset = replayTest(ctx, plan, ctx.preset(), counters);
        Double gainPreset = testPreset.medianGainPct();
        if (o.blocked()) { // rien de retenable : le pli garde le preset en place, jamais un candidat vetoé ni invalide
            String why = chosenEval.vetoed() ? "tous les candidats vetoés à l'entraînement : " + chosenEval.vetoes()
                    : "preset en place vetoé à l'entraînement : " + o.trainStart().vetoes();
            return new FoldResult(plan.trainEnd(), plan.testFrom(), plan.testEnd(), true, FOLD_KEEP, OptimizerDefaults.FOLD_TRAIN_VETOED, why, null,
                    chosenEval.score(), o.trainStart().score(), chosenEval.vetoes(), o.trainStart().vetoes(), 0, 0, 0, false, 0, frozen,
                    r.sensitivities(), r.trials(), r.vetoCounts(), null, gainPreset, null, null, exposure(testPreset), null,
                    timing(testPreset), null, List.of(), testPreset.vetoes(), false, false, trainFp, testFp);
        }
        ScoreReport testChallenger = replayTest(ctx, plan, PresetParams.apply(ctx.preset(), r.chosen()), counters);
        Double gainChallenger = testChallenger.medianGainPct();
        boolean evaluated = gainPreset != null && gainChallenger != null;
        Double delta = evaluated ? gainChallenger - gainPreset : null;
        boolean testVeto = evaluated && testChallenger.vetoes().stream().anyMatch(TEST_BLOCKING_VETOES::contains);
        boolean won = evaluated && !testVeto && delta > 0;
        Double timingChallenger = timing(testChallenger);
        Double timingPreset = timing(testPreset);
        return new FoldResult(plan.trainEnd(), plan.testFrom(), plan.testEnd(), true, FOLD_CHALLENGER, null,
                evaluated ? null : "aucune fenêtre de test rejouable : " + testChallenger.excluded(), r.chosen().values(), chosenEval.score(),
                o.trainStart().score(), chosenEval.vetoes(), o.trainStart().vetoes(), r.plateau().plateauScore(), r.plateau().size(),
                r.plateau().neighbors(), r.robustness().robust(), r.robustness().worstRelativeGap(), frozen, r.sensitivities(), r.trials(),
                r.vetoCounts(), gainChallenger, gainPreset, delta, exposure(testChallenger), exposure(testPreset), timingChallenger, timingPreset,
                timingChallenger != null && timingPreset != null ? timingChallenger - timingPreset : null, testChallenger.vetoes(),
                testPreset.vetoes(), testVeto, won, trainFp, testFp);
    }

    private static Double exposure(ScoreReport r) {
        return r.windows().isEmpty() ? null : r.windows().getFirst().avgExposurePct();
    }

    private static Double timing(ScoreReport r) {
        return r.pureTiming() == null ? null : r.pureTiming().weightedMedianPct();
    }

    private static List<CandleFingerprint> fingerprints(ScorerInputs in, LocalDate end) {
        return in.assets().stream().map(a -> CandleFingerprint.of(a.slot().assetSymbol(), a.template().candles(), end)).toList();
    }

    // ------------------------------------------------------------------ cadences de ré-optimisation

    static int segmentCount(LocalDate start, LocalDate end, int days) {
        int k = 0;
        while (!start.plusDays((long) (k + 1) * days).isAfter(end)) {
            k++;
        }
        return k;
    }

    /** Chaîne de challengers d'un actif : ré-optimise à chaque cadence, applique le challenger aux jours suivants ; preset statique en face. */
    private CadenceAsset chain(Ctx ctx, int days, PresetOptimizerConfig cfg, LocalDate end, SearchControl control, Progress progress,
                               Map<LocalDate, Outcome> memo, Counters counters, List<SearchTiming> timings) {
        List<Segment> segments = new ArrayList<>();
        int planned = segmentCount(cfg.cadenceStart(), end, days);
        int challenger = 0;
        int kept = 0;
        int skipped = 0;
        int positive = 0;
        int vetoed = 0;
        double gainPreset = 0;
        double gainProcess = 0;
        for (int k = 0; k < planned; k++) {
            control.throwIfCancelled();
            LocalDate trainEnd = cfg.cadenceStart().plusDays((long) k * days);
            LocalDate to = trainEnd.plusDays(days);
            Plan plan = plan(ctx, trainEnd, to, SEGMENT_MARKER_MONTHS);
            if (!plan.usable() || ctx.built().space().free().isEmpty()) {
                segments.add(skippedSegment(plan, OptimizerDefaults.STATUS_INSUFFICIENT_HISTORY));
                skipped++;
                progress.step(ctx.asset() + " " + days + " j " + trainEnd + " : non exploitable");
                continue;
            }
            Outcome outcome = outcome(ctx, plan, cfg, control, progress, memo, counters, timings);
            ScoreReport preset = replayTest(ctx, plan, ctx.preset(), counters);
            Double gp = preset.medianGainPct();
            if (gp == null) {
                segments.add(skippedSegment(plan, "aucune fenêtre rejouable"));
                skipped++;
            } else if (outcome.blocked()) {
                segments.add(new Segment(trainEnd, plan.testFrom(), to, FOLD_KEEP, OptimizerDefaults.FOLD_TRAIN_VETOED, gp, gp, 0.0, List.of()));
                gainPreset += gp;
                gainProcess += gp;
                kept++;
            } else {
                ScoreReport challengerReport = replayTest(ctx, plan, PresetParams.apply(ctx.preset(), outcome.result().chosen()), counters);
                Double gc = challengerReport.medianGainPct();
                if (gc == null) {
                    segments.add(skippedSegment(plan, "aucune fenêtre rejouable"));
                    skipped++;
                } else {
                    boolean veto = challengerReport.vetoes().stream().anyMatch(TEST_BLOCKING_VETOES::contains);
                    segments.add(new Segment(trainEnd, plan.testFrom(), to, FOLD_CHALLENGER, null, gp, gc, gc - gp, challengerReport.vetoes()));
                    gainPreset += gp;
                    gainProcess += gc;
                    challenger++;
                    positive += gc > gp ? 1 : 0;
                    vetoed += veto ? 1 : 0;
                }
            }
            progress.step(ctx.asset() + " " + days + " j " + trainEnd + " : segment terminé");
        }
        int evaluated = challenger + kept;
        double blockedShare = evaluated == 0 ? 0.0 : (double) kept / evaluated;
        double delta = gainProcess - gainPreset;
        boolean beats = evaluated > 0 && delta > 0 && blockedShare < OptimizerDefaults.PRESET_MAX_TRAIN_BLOCKED_SHARE;
        return new CadenceAsset(ctx.asset(), planned, evaluated, challenger, kept, skipped, blockedShare, gainPreset, gainProcess, delta, positive,
                vetoed, beats, segments);
    }

    private static Segment skippedSegment(Plan plan, String reason) {
        return new Segment(plan.trainEnd(), plan.testFrom(), plan.testEnd(), "NON_EVALUE", reason, null, null, null, List.of());
    }

    /** Le processus (ré-optimiser tous les X jours) bat le preset statique si TOUS les actifs évalués le battent avec des segments exploitables. */
    private static CadenceReport cadenceReport(int days, List<CadenceAsset> assets) {
        List<CadenceAsset> evaluated = assets.stream().filter(a -> a.segmentsEvaluated() > 0).toList();
        List<String> reasons = new ArrayList<>();
        for (CadenceAsset a : assets) {
            reasons.add(a.segmentsEvaluated() == 0 ? a.asset() + " : aucun segment évalué"
                    : String.format(java.util.Locale.ROOT, "%s : écart cumulé %+.2f pt du capital, %d/%d segments meilleurs que le preset, "
                    + "entraînement vetoé %.0f %%, %d segments en veto de test", a.asset(), a.cumulativeDeltaPts(), a.positiveSegments(),
                    a.segmentsEvaluated(), 100 * a.trainBlockedShare(), a.testVetoSegments()));
        }
        boolean beats = !evaluated.isEmpty() && evaluated.stream().allMatch(CadenceAsset::processBeatsStatic);
        return new CadenceReport(days, assets, beats, reasons);
    }

    // ------------------------------------------------------------------ budget

    /**
     * Estimation du coût de la comparaison des cadences, à partir des durées mesurées du walk-forward simple (même pool, mêmes essais par
     * recherche). Les recherches de fins d'entraînement communes à plusieurs cadences ne sont comptées qu'une fois.
     */
    public static BudgetEstimate estimateCadenceBudget(PresetOptimizationReport walkForward, PresetOptimizerConfig cfg, LocalDate end) {
        List<SearchTiming> searches = walkForward.timings() == null ? List.of() : walkForward.timings().searches();
        if (searches.isEmpty()) {
            throw new IllegalArgumentException("Aucune recherche chronométrée : lancer d'abord le walk-forward simple");
        }
        int assets = (int) walkForward.assets().stream().filter(a -> OptimizerDefaults.STATUS_OPTIMIZABLE.equals(a.status())).count();
        Set<LocalDate> dates = new LinkedHashSet<>();
        long segments = 0;
        for (int days : cfg.cadenceDays()) {
            int n = segmentCount(cfg.cadenceStart(), end, days);
            segments += n;
            for (int k = 0; k < n; k++) {
                dates.add(cfg.cadenceStart().plusDays((long) k * days));
            }
        }
        double avgTrials = searches.stream().mapToLong(SearchTiming::trials).average().orElse(0);
        double avgMs = searches.stream().mapToLong(SearchTiming::searchMs).average().orElse(0);
        long totalSearches = (long) dates.size() * assets;
        long oos = segments * assets * REPLAYS_PER_EVALUATED_SEARCH;
        return new BudgetEstimate(cfg.cadenceDays(), assets, dates.size(), totalSearches, avgTrials, avgMs, Math.round(totalSearches * avgTrials), oos,
                totalSearches * avgMs / 60_000.0);
    }

    /**
     * Budget de la comparaison des cadences : recherches distinctes (fins d'entraînement uniques × actifs optimisables), essais et rejeux hors
     * échantillon attendus, minutes estimées (durée moyenne mesurée d'une recherche, pool inclus ; rejeux hors échantillon négligés).
     */
    public record BudgetEstimate(List<Integer> cadences, int optimizableAssets, int distinctTrainEnds, long searches, double trialsPerSearch,
                                 double msPerSearch, long estimatedTrials, long estimatedOosReplays, double estimatedMinutes) {
    }
}
