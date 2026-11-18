package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.LinkOverride;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.expert.simulation.FullSimulationService;
import fr.ses10doigts.tradeIO5.service.optimizer.ScoreReport.WindowReport;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.FoldResult;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.ProfileReport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runner manuel (DB dev = copie de prod ; jamais dans la suite normale) : diagnostic de reproductibilité (mode {@code repro}) et
 * démo L2 réelle (mode {@code l2}) de l'optimiseur, calibrage walk-forward de la politique L2 (mode {@code policy}).
 * <p>
 * Lancement : {@code mvn test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=repro|l2|policy|preset|preset-cadences|sensitivity} (ignoré sans la
 * propriété). {@code preset} = optimiseur de presets L1, walk-forward simple + estimation du coût des cadences ; {@code preset-cadences} =
 * comparaison des cadences 30 / 90 / 180 j (coûteuse : seulement après accord).
 * Sorties : {@code target/optimizer-repro.md}, {@code target/optimizer-l2.md}, {@code target/optimizer-<ACTIF>.json},
 * {@code target/policy-calibration.md|json}, {@code target/preset-optimization.md|json}, {@code target/preset-cadences.md|json}.
 */
@SpringBootTest(properties = {
        "logging.level.fr.ses10doigts.tradeIO5=INFO",
        "tradeio.optimizer.enabled=true",
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:localhost}:3306/tradeio5?rewriteBatchedStatements=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=klm",
        "spring.datasource.password=klm31",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@EnabledIfSystemProperty(named = "optimizer.manual", matches = "repro|l2|policy|policy-variants|preset|preset-cadences|sensitivity")
class OptimizerManualRunnerTest {

    private static final String USER = "OKlm";
    private static final String PROFILE = "BALANCED";
    private static final LocalDate TEST_END = LocalDate.parse("2026-11-15");
    private static final int POOL = Integer.getInteger("optimizer.pool", 4);
    /** Paramètres gelés à leur valeur de départ ({@code -Doptimizer.freeze=baseMultiplier,buyCap}) ; suffixe des fichiers de sortie. */
    private static final List<String> FROZEN = java.util.Arrays.stream(System.getProperty("optimizer.freeze", "").split(",")).filter(x -> !x.isBlank()).toList();
    private static final String SUFFIX = FROZEN.isEmpty() ? "" : "-gel-" + String.join("-", FROZEN);

    /** Actif, wallet, lien, capital ; fin d'entraînement = début de la fenêtre hors échantillon ; fenêtres d'entraînement. */
    private record Case(String asset, long walletId, long bindingId, double cash0, LocalDate trainEnd, List<WindowSpec> windows) {
    }

    private static final List<Case> CASES = List.of(
            new Case("BTC", 1L, 1L, 2484.0, LocalDate.parse("2025-11-15"), WindowSpec.defaults()),
            new Case("ETH", 1L, 3L, 2425.0, LocalDate.parse("2025-11-15"), WindowSpec.defaults()),
            new Case("PAXG", 3L, 5L, 1062.0, LocalDate.parse("2025-11-15"), WindowSpec.defaults()));

    @Autowired
    private ReplayInputsLoader loader;
    @Autowired
    private FullSimulationService full;
    @Autowired
    private UserTradingSettingsService settingsService;
    @Autowired
    private UserRepository users;

    private static boolean mode(String m) {
        return m.equals(System.getProperty("optimizer.manual"));
    }

    // ---------------------------------------------------------------- repro

    @Test
    @DisplayName("Diagnostic : d'où viennent les écarts de reproductibilité entre deux mesures « identiques »")
    void reproducibilityGap() throws IOException {
        Assumptions.assumeTrue(mode("repro"));
        User user = users.findByUsername(USER).orElseThrow();
        List<String> out = new ArrayList<>();
        out.add("## Défauts du simulateur complet (BTC, wallet 1, aucun paramètre fourni)");
        FullSimulationService.Plan defaults = full.resolve(new ExpertContext(user, true), new FullRequest(1L, null, null, null, null, null,
                null, null, null, false, List.of(new LinkOverride(1L, true, null, null)), null, null));
        FullSimulationService.Loaded real = full.load(user, defaults, new FullSimulationService.Hooks(() -> {
        }, () -> {
        }, () -> false));
        out.add("- plage par défaut : " + defaults.from() + " → " + defaults.to() + " (glisse avec l'horloge)");
        out.add("- capital : plan=" + defaults.cash0() + " ; chargé=" + String.format(Locale.ROOT, "%.2f", real.cash0())
                + " (valeur du wallet réel du jour)");
        out.add("- réglages de modulation utilisateur effectifs : " + settingsService.effectiveSettings(user));
        out.add("");
        out.add("## Scorer, entrées explicites (BTC, fenêtres 12m / 24m / full)");
        out.add("| Cas | Capital | Fin | Final 24m | DCA fixe 24m | Gain 24m % | Empreinte |");
        out.add("|---|---|---|---|---|---|---|");
        ResolvedCandidate constant = L2Params.adapter(fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile.BALANCED)
                .resolve(L2Params.space(fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile.BALANCED).startCandidate());
        double[] capitals = {2484.0, Math.round(real.cash0() * 100) / 100.0};
        LocalDate[] ends = {TEST_END, TEST_END.minusDays(1), TEST_END.minusDays(30)};
        for (double cash : capitals) {
            for (LocalDate end : ends) {
                ScorerInputs in = loader.load(user, new OptimizerRunRequest("BTC", 1L, cash, end, PROFILE, null, null, null));
                ScoreReport a = ReplayScorer.score(in, constant, CostMode.SIMPLIFIED, false);
                ScoreReport b = ReplayScorer.score(in, constant, CostMode.SIMPLIFIED, false);
                WindowReport w = a.windows().stream().filter(x -> "24m".equals(x.name())).findFirst().orElseThrow();
                out.add(String.format(Locale.ROOT, "| %s | %.2f | %s | %.0f | %.0f | %+.2f | %s |", a.equals(b) ? "2 runs identiques" : "2 runs DIFFÉRENTS",
                        cash, end, w.finalValue(), w.fixedFinalValue(), w.gainPct(), a.fingerprint().getFirst()));
            }
        }
        Files.writeString(Path.of("target/optimizer-repro.md"), String.join("\n", out) + "\n");
        out.forEach(System.out::println);
    }

    // ---------------------------------------------------------------- démo L2

    @Test
    @DisplayName("Démo L2 : bandes par régime, plafond ATH, montant de base ; BTC / ETH / PAXG, profil Équilibré")
    void l2Demo() throws IOException {
        Assumptions.assumeTrue(mode("l2"));
        User user = users.findByUsername(USER).orElseThrow();
        List<String> md = new ArrayList<>();
        md.add("Gelés : " + FROZEN + ". Pool d'évaluation : " + POOL + " threads ; profil " + PROFILE + " ; graine " + OptimizerDefaults.SEED + ".");
        ExecutorService pool = Executors.newFixedThreadPool(POOL);
        try {
            for (Case c : CASES) {
                md.addAll(demo(user, c, pool));
            }
        } finally {
            pool.shutdownNow();
        }
        Files.writeString(Path.of("target/optimizer-l2" + SUFFIX + ".md"), String.join("\n", md) + "\n");
        md.forEach(System.out::println);
    }

    private List<String> demo(User user, Case c, ExecutorService pool) throws IOException {
        List<String> md = new ArrayList<>();
        md.add("");
        md.add("## " + c.asset());
        ScorerInputs train;
        try {
            train = loader.load(user, new OptimizerRunRequest(c.asset(), c.walletId(), c.cash0(), c.trainEnd(), PROFILE, null, null, null))
                    .withWindows(c.windows());
        } catch (RuntimeException e) {
            md.add("ERREUR de chargement : " + e.getMessage());
            return md;
        }
        var profile = train.profile();
        ParamSpace space = L2Params.space(profile).freezing(FROZEN);
        SearchConfig config = SearchConfig.defaults();
        ReplayObjective objective = new ReplayObjective(train, L2Params.adapter(profile), L2Params.validator(train.limits()), OptimizerDefaults.BUMP_DAYS, java.util.Map.of());
        SearchResult result = new SearchEngine(pool).run(space, objective, config, SearchControl.none());
        OptimizationReport report = OptimizationReport.of(c.asset() + " " + PROFILE, result, config, train);
        Files.writeString(Path.of("target/optimizer-" + c.asset() + SUFFIX + ".json"), report.toJson());

        md.add("Entraînement : fenêtres finissant le " + c.trainEnd() + " ; capital " + c.cash0() + " USDC ; " + result.trials() + " essais ; "
                + result.timings().totalMs() + " ms au total ; " + String.format(Locale.ROOT, "%.1f", result.timings().msPerRun()) + " ms/run.");
        md.add("Sensibilités (effet en points de score) : " + result.sensitivities().stream()
                .map(s -> s.name() + "=" + String.format(Locale.ROOT, "%.2f", s.effect()) + (s.frozen() ? " (gelé)" : "")).toList());
        md.add("Retenu : " + result.chosen().values() + " ; score " + String.format(Locale.ROOT, "%.2f", result.chosenEvaluation().score())
                + " ; plateau " + String.format(Locale.ROOT, "%.2f", result.plateau().plateauScore()) + " (" + result.plateau().size() + "/"
                + result.plateau().neighbors() + " voisins) ; robuste=" + result.robustness().robust() + " (pire écart "
                + String.format(Locale.ROOT, "%.0f", result.robustness().worstRelativeGap() * 100) + " %).");
        ScoreReport chosen = (ScoreReport) result.chosenEvaluation().detail();
        md.addAll(windowTable("Entraînement, candidat retenu", chosen));
        md.add("Timing pur (hors score) : " + (chosen.pureTiming() == null ? "n/a" : String.format(Locale.ROOT, "%+.2f %%", chosen.pureTiming().weightedMedianPct())));
        md.add("Bousculades : " + result.bumps().stream().map(b -> b.label() + " " + String.format(Locale.ROOT, "%.2f", b.score())
                + (b.vetoed() ? " VETO" : "") + (b.tolerated() ? "" : " HORS TOLÉRANCE")).toList());
        md.add("Vetos rencontrés : " + result.vetoCounts());

        // hors échantillon : les 12 mois qui suivent la fin d'entraînement, même candidat, mêmes entrées (bougies déjà chargées)
        ScorerInputs test = train.withEndDate(TEST_END).withWindows(List.of(new WindowSpec("hors-échantillon 12m", 12, 1)));
        ResolvedCandidate retained = L2Params.adapter(profile).resolve(result.chosen());
        ResolvedCandidate start = L2Params.adapter(profile).resolve(space.startCandidate());
        md.addAll(windowTable("Hors échantillon (" + c.trainEnd() + " → " + TEST_END + "), candidat retenu",
                ReplayScorer.score(test, retained, CostMode.COMPLETE, false)));
        md.addAll(windowTable("Hors échantillon, point de départ (bande 20–90 / 0–30, plafond du profil)",
                ReplayScorer.score(test, start, CostMode.COMPLETE, false)));

        // budget de calcul : durée d'un run par fidélité, mono-thread
        md.add("Durée d'un run (mono-thread, 5 essais) : " + budgetLine(objective, result.chosen()));
        return md;
    }

    private static String budgetLine(SearchObjective objective, Candidate candidate) {
        StringBuilder sb = new StringBuilder();
        for (Fidelity f : Fidelity.values()) {
            objective.evaluate(candidate, f, Scenario.BASE);
            long t0 = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                objective.evaluate(candidate, f, Scenario.BASE);
            }
            double ms = (System.nanoTime() - t0) / 5e6;
            sb.append(String.format(Locale.ROOT, "%s %.1f ms (%.0f runs/h/thread) ; ", f, ms, 3.6e6 / ms));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- carte de sensibilité (étape A)

    @Test
    @DisplayName("Carte de sensibilité Rainbow BTC : Bull seul / Bear seul, un réglage à la fois, PnL réel sur 24 mois glissants à J-1")
    void sensitivityMap() throws IOException {
        Assumptions.assumeTrue(mode("sensitivity"));
        User user = users.findByUsername(USER).orElseThrow();
        LocalDate end = LocalDate.parse(System.getProperty("optimizer.end", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString()));
        Case c = CASES.getFirst();
        ScorerInputs in = loader.load(user, new OptimizerRunRequest(c.asset(), c.walletId(), c.cash0(), end, PROFILE, null, null, null));
        SensitivityReport report = SensitivityMap.run(in, SensitivityMap.VIEW_MONTHS);
        Files.writeString(Path.of("target/sensitivity-rainbow.json"), report.toJson());
        Files.writeString(Path.of("target/sensitivity-rainbow.md"), report.toMarkdown());
        System.out.println(report.toMarkdown());
    }

    // ---------------------------------------------------------------- presets L1 (étape 5)

    private ReferenceWallet presetWallet(User user, PresetOptimizerConfig config, LocalDate end) {
        return loader.loadReferenceWallet(user, OptimizerRunRequest.presetL1(null, config.profile().name(), List.of(
                new OptimizerRunRequest.LinkRef("BTC", 1L), new OptimizerRunRequest.LinkRef("ETH", 1L),
                new OptimizerRunRequest.LinkRef("PAXG", 3L)), null, null, null), end);
    }

    @Test
    @DisplayName("Presets L1 : walk-forward simple par actif (BTC, ETH, PAXG), challenger seulement, + estimation du coût des cadences")
    void presetOptimization() throws IOException {
        Assumptions.assumeTrue(mode("preset"));
        User user = users.findByUsername(USER).orElseThrow();
        PresetOptimizerConfig config = PresetOptimizerConfig.defaults();
        ReferenceWallet wallet = presetWallet(user, config, config.lastTestEnd());
        ExecutorService pool = Executors.newFixedThreadPool(POOL);
        PresetOptimizationReport report;
        try {
            report = new PresetOptimizer(pool, PresetSpaceSource.defaults()).run(wallet, config, SearchControl.none());
        } finally {
            pool.shutdownNow();
        }
        Files.writeString(Path.of("target/preset-optimization.json"), report.toJson());
        List<String> md = presetMarkdown(report);
        LocalDate cadenceEnd = TEST_END;
        PresetOptimizer.BudgetEstimate budget = PresetOptimizer.estimateCadenceBudget(report, config, cadenceEnd);
        md.add("");
        md.add("## Budget estimé de la comparaison des cadences " + budget.cadences() + " j (NON lancée : accord requis)");
        md.add(String.format(Locale.ROOT, "%d actifs optimisables ; %d fins d'entraînement distinctes ; %d recherches ; ~%d essais (%.0f / recherche) ; "
                        + "%.0f s par recherche (pool %d) ; ~%d rejeux hors échantillon ; durée estimée ~%.1f min.", budget.optimizableAssets(),
                budget.distinctTrainEnds(), budget.searches(), budget.estimatedTrials(), budget.trialsPerSearch(), budget.msPerSearch() / 1000, POOL,
                budget.estimatedOosReplays(), budget.estimatedMinutes()));
        Files.writeString(Path.of("target/preset-optimization.md"), String.join("\n", md) + "\n");
        md.forEach(System.out::println);
    }

    @Test
    @DisplayName("Presets L1 : cadences 30 / 90 / 180 j contre le preset statique (coûteux : à lancer après accord sur le budget)")
    void presetCadences() throws IOException {
        Assumptions.assumeTrue(mode("preset-cadences"));
        User user = users.findByUsername(USER).orElseThrow();
        PresetOptimizerConfig config = PresetOptimizerConfig.defaults();
        ReferenceWallet wallet = presetWallet(user, config, TEST_END);
        ExecutorService pool = Executors.newFixedThreadPool(POOL);
        PresetOptimizationReport report;
        try {
            report = new PresetOptimizer(pool, PresetSpaceSource.defaults()).runWithCadences(wallet, config, SearchControl.none());
        } finally {
            pool.shutdownNow();
        }
        Files.writeString(Path.of("target/preset-cadences.json"), report.toJson());
        List<String> md = presetMarkdown(report);
        Files.writeString(Path.of("target/preset-cadences.md"), String.join("\n", md) + "\n");
        md.forEach(System.out::println);
    }

    private static List<String> presetMarkdown(PresetOptimizationReport r) {
        List<String> md = new ArrayList<>();
        var in = r.inputs();
        var mult = r.multiplicity();
        md.add("Profil " + in.profile() + " (bande d'exposition " + in.exposureBandMinPct() + "–" + in.exposureBandMaxPct() + " %, plafond de drawdown "
                + in.drawdownCapPct() + " %) ; capital " + in.cash0() + " USDC ; référence DCA fixe autonomie " + in.autonomyDays() + " j ; graine " + in.seed()
                + " ; δ " + in.space().defaultDelta() + " ; pool " + POOL + " threads ; temps total " + r.timings().totalMs() + " ms.");
        md.add("Multiplicité : " + mult.assetsConsidered() + " actifs (" + mult.assetsOptimized() + " optimisés), " + mult.searches() + " recherches, "
                + mult.searchTrials() + " essais, " + mult.oosReplays() + " rejeux hors échantillon, cadences " + mult.cadencesCompared() + ". "
                + r.significanceNote());
        for (var a : r.assets()) {
            md.add("");
            md.add("## " + a.asset() + " — " + a.status() + (a.statusReason() == null ? "" : " (" + a.statusReason() + ")") + " — " + a.verdict().decision());
            md.add("Source " + a.source() + " ; motif : " + String.join(" ; ", a.verdict().reasons()));
            if (!a.history().isEmpty()) {
                md.add("Historique : " + a.history());
            }
            md.add("Preset en place : " + a.inPlace());
            md.add("Gelés : " + a.params().stream().filter(PresetParams.ParamInfo::frozen).map(p -> p.name() + " (" + p.reason() + ")").toList());
            md.add("| Pli (fin) | Décision | Gain OOS challenger % | Gain OOS preset % | Δ pts | Expo moy. chall. / preset % | Timing pur chall. / preset % | "
                    + "Excès timing pts | Vetos test chall. | Essais | Gagné |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|");
            for (var f : a.folds()) {
                md.add("| " + f.trainEnd() + " | " + f.foldDecision() + (f.foldReason() == null ? "" : " [" + f.foldReason() + "]") + " | " + pct(f.testGainChallengerPct())
                        + " | " + pct(f.testGainPresetPct()) + " | " + pct(f.deltaPts()) + " | " + pct(f.avgExposureChallengerPct()) + " / " + pct(f.avgExposurePresetPct())
                        + " | " + pct(f.pureTimingChallengerPct()) + " / " + pct(f.pureTimingPresetPct()) + " | " + pct(f.timingExcessPts()) + " | "
                        + f.testVetoesChallenger() + (f.testVeto() ? " BLOQUANT" : "") + " | " + f.trials() + " | " + (f.won() ? "oui" : "non") + " |");
            }
            md.add("Verdict : " + a.verdict().decision() + " — plis gagnés " + a.verdict().wins() + "/" + a.verdict().usableFolds() + ", entraînement vetoé "
                    + a.verdict().trainBlockedFolds() + "/" + a.verdict().usableFolds() + ", médiane Δ OOS " + pct(a.verdict().medianDeltaPts()) + " pts.");
        }
        for (var c : r.cadences()) {
            md.add("");
            md.add("## Cadence " + c.cadenceDays() + " j — processus bat le preset statique : " + c.processBeatsStatic());
            c.reasons().forEach(x -> md.add("- " + x));
            md.add("| Actif | Segments (évalués / prévus) | Challenger / preset gardé | Gain preset statique % | Gain chaîne % | Δ cumulé pts | Segments > preset | Entr. vetoé % |");
            md.add("|---|---|---|---|---|---|---|---|");
            c.assets().forEach(a -> md.add("| " + a.asset() + " | " + a.segmentsEvaluated() + " / " + a.segmentsPlanned() + " | " + a.segmentsChallenger() + " / "
                    + a.segmentsKeptPreset() + " | " + pct(a.cumulativeGainPresetPct()) + " | " + pct(a.cumulativeGainProcessPct()) + " | " + pct(a.cumulativeDeltaPts())
                    + " | " + a.positiveSegments() + " | " + String.format(Locale.ROOT, "%.0f", 100 * a.trainBlockedShare()) + " |"));
        }
        return md;
    }

    // ---------------------------------------------------------------- calibrage de la politique L2 (étape 4)

    @Test
    @DisplayName("Calibrage L2 : 4 profils, plis walk-forward annuels, wallet de référence BTC + ETH + PAXG à cash commun")
    void policyCalibration() throws IOException {
        Assumptions.assumeTrue(mode("policy"));
        User user = users.findByUsername(USER).orElseThrow();
        ReferenceWallet wallet = loader.loadReferenceWallet(user, OptimizerRunRequest.policyL2(null, null, List.of(
                new OptimizerRunRequest.LinkRef("BTC", 1L), new OptimizerRunRequest.LinkRef("ETH", 1L),
                new OptimizerRunRequest.LinkRef("PAXG", 3L)), null, null, null));
        ExecutorService pool = Executors.newFixedThreadPool(POOL);
        PolicyCalibrationReport report;
        try {
            report = new PolicyCalibrator(pool).run(wallet, CalibrationConfig.defaults(), SearchControl.none());
        } finally {
            pool.shutdownNow();
        }
        Files.writeString(Path.of("target/policy-calibration.json"), report.toJson());
        List<String> md = policyMarkdown(report);
        Files.writeString(Path.of("target/policy-calibration.md"), String.join("\n", md) + "\n");
        md.forEach(System.out::println);
    }

    /** Variantes du protocole (mêmes entrées, même graine) : une seule chargement du wallet, un fichier par variante + synthèse. */
    private record Variant(String name, boolean trainDd, boolean dropOnStart, int autonomy, java.util.Map<String, Double> start, int testMonths,
                           List<LocalDate> foldEnds, long seed) {
        Variant(String name, boolean trainDd, boolean dropOnStart, int autonomy) {
            this(name, trainDd, dropOnStart, autonomy, java.util.Map.of(), 0, List.of(), 0L);
        }

        Variant(String name, boolean trainDd, boolean dropOnStart, int autonomy, java.util.Map<String, Double> start, int testMonths,
                List<LocalDate> foldEnds) {
            this(name, trainDd, dropOnStart, autonomy, start, testMonths, foldEnds, 0L);
        }
    }

    @Test
    @DisplayName("Variantes du calibrage L2 : veto d'entraînement, pli conservé, autonomie de la référence")
    void policyVariants() throws IOException {
        Assumptions.assumeTrue(mode("policy-variants"));
        User user = users.findByUsername(USER).orElseThrow();
        ReferenceWallet base = loader.loadReferenceWallet(user, OptimizerRunRequest.policyL2(null, null, List.of(
                new OptimizerRunRequest.LinkRef("BTC", 1L), new OptimizerRunRequest.LinkRef("ETH", 1L),
                new OptimizerRunRequest.LinkRef("PAXG", 3L)), null, null, null));
        int defaultAutonomy = base.autonomyDays();
        List<LocalDate> semi = List.of(LocalDate.parse("2023-05-15"), LocalDate.parse("2023-11-15"), LocalDate.parse("2024-05-15"),
                LocalDate.parse("2024-11-15"), LocalDate.parse("2025-05-15"), LocalDate.parse("2025-11-15"));
        List<Variant> all = new ArrayList<>(List.of(new Variant("V0-base", true, true, defaultAutonomy),
                new Variant("V1-sans-veto-entrainement", false, true, defaultAutonomy),
                new Variant("V2-pli-garde-si-depart-vetoe", true, false, defaultAutonomy),
                new Variant("V3-V1+V2", false, false, defaultAutonomy),
                new Variant("V4-reference-360j", true, true, 360),
                new Variant("V5-V1+V2+reference-360j", false, false, 360),
                new Variant("V6-V2+reference-360j", true, false, 360),
                new Variant("W1-V6+depart-multiple-0.5", true, false, 360, java.util.Map.of(L2Params.BASE_MULTIPLIER, 0.5), 0, List.of()),
                new Variant("W2-V6+depart-bandes-defensives", true, false, 360,
                        java.util.Map.of(L2Params.BULL_MAX, 70.0, L2Params.BEAR_MAX, 15.0), 0, List.of()),
                new Variant("W3-V6+W1+W2", true, false, 360, java.util.Map.of(L2Params.BASE_MULTIPLIER, 0.5, L2Params.BULL_MAX, 70.0,
                        L2Params.BEAR_MAX, 15.0), 0, List.of()),
                new Variant("A1-V6+autonomie-180j", true, false, 180),
                new Variant("A2-V6+autonomie-720j", true, false, 720),
                new Variant("M6-V6+6-plis-test-6-mois", true, false, 360, java.util.Map.of(), 6, semi),
                new Variant("X1-W1+6-plis", true, false, 360, java.util.Map.of(L2Params.BASE_MULTIPLIER, 0.5), 6, semi),
                new Variant("X2-W3+6-plis", true, false, 360, java.util.Map.of(L2Params.BASE_MULTIPLIER, 0.5, L2Params.BULL_MAX, 70.0,
                        L2Params.BEAR_MAX, 15.0), 6, semi),
                new Variant("X3-A2+6-plis", true, false, 720, java.util.Map.of(), 6, semi)));
        for (long seed : new long[]{111L, 222L, 333L, 444L}) {
            all.add(new Variant("S" + seed + "-M6", true, false, 360, java.util.Map.of(), 6, semi, seed));
            all.add(new Variant("S" + seed + "-X2", true, false, 360, java.util.Map.of(L2Params.BASE_MULTIPLIER, 0.5, L2Params.BULL_MAX, 70.0,
                    L2Params.BEAR_MAX, 15.0), 6, semi, seed));
        }
        List<Variant> variants = all.stream()
                .filter(v -> System.getProperty("optimizer.only", "").isBlank()
                        || java.util.Arrays.stream(System.getProperty("optimizer.only").split(",")).anyMatch(v.name()::startsWith)).toList();
        List<String> summary = new ArrayList<>();
        summary.add("| Variante | Départ vetoé entr. | Plis GARDER_DEPART/ENTR. | Plis gagnés P/E/D/F | Médiane OOS % P/E/D/F | Plis avec veto test | Pénalité BTC/ETH (part) | Verdicts |");
        summary.add("|---|---|---|---|---|---|---|---|");
        for (Variant v : variants) {
            ReferenceWallet wallet = new ReferenceWallet(base.inputs(), v.autonomy());
            ExecutorService pool = Executors.newFixedThreadPool(POOL);
            PolicyCalibrationReport r;
            try {
                r = new PolicyCalibrator(pool).run(wallet, configOf(v), SearchControl.none());
            } finally {
                pool.shutdownNow();
            }
            Files.writeString(Path.of("target/policy-" + v.name() + ".md"), String.join("\n", policyMarkdown(r)) + "\n");
            int folds = r.profiles().stream().mapToInt(p -> p.folds().size()).sum();
            long startVetoed = r.profiles().stream().flatMap(p -> p.folds().stream()).filter(f -> !f.trainVetoesStart().isEmpty()).count();
            long blocked = r.profiles().stream().flatMap(p -> p.folds().stream()).filter(f -> OptimizerDefaults.FOLD_TRAIN_VETOED.equals(f.foldReason())).count();
            long testVeto = r.profiles().stream().flatMap(p -> p.folds().stream()).filter(f -> f.testVeto()).count();
            summary.add("| " + v.name() + " | " + startVetoed + "/" + folds + " | " + blocked + "/" + folds + " | "
                    + String.join("/", r.profiles().stream().map(p -> p.verdict().wins() + "/" + p.folds().size()).toList()) + " | "
                    + String.join(" / ", r.profiles().stream().map(p -> pct(p.verdict().medianTestGainPct())).toList()) + " | " + testVeto + "/" + folds + " | "
                    + String.join(" ; ", r.penaltyMeasure().stream().map(s -> s.asset() + " " + String.format(Locale.ROOT, "%.0f %%",
                    100 * (s.medianShareOfAbsScore() == null ? 0 : s.medianShareOfAbsScore()))).toList()) + " | "
                    + String.join(" ", r.profiles().stream().map(p -> p.verdict().decision()).toList()) + " |");
            System.out.println("VARIANT " + v.name() + " " + summary.getLast());
        }
        Files.writeString(Path.of("target/policy-variants.md"), String.join("\n", summary) + "\n");
    }

    private static CalibrationConfig configOf(Variant v) {
        CalibrationConfig c = CalibrationConfig.defaults().withVariant(v.trainDd(), v.dropOnStart()).withStart(v.start());
        if (v.seed() != 0L) {
            c = c.withSeed(v.seed());
        }
        return v.foldEnds().isEmpty() ? c : c.withFolds(v.foldEnds(), v.testMonths());
    }

    private static String pct(Double v) {
        return v == null ? "n/a" : String.format(Locale.ROOT, "%+.2f", v);
    }

    private static List<String> policyMarkdown(PolicyCalibrationReport r) {
        List<String> md = new ArrayList<>();
        var in = r.inputs();
        md.add("Capital " + in.cash0() + " USDC ; autonomie " + in.autonomyDays() + " j ; graine " + in.seed() + " ; pool " + POOL + " threads ; λ "
                + in.dispersionPenalty() + " ; essais (tous plis et profils) " + r.trials() + " ; rejeux hors recherche " + r.evaluationsOutsideSearch()
                + " ; temps total " + r.timings().totalMs() + " ms.");
        md.add("");
        md.add("## Liens et sources");
        in.links().forEach(l -> md.add("- " + l.asset() + " (lien #" + l.bindingId() + ") : source " + l.source() + ", multiplicateur moyen "
                + l.meanMultiplier() + ", base de référence (wallet complet) " + l.referenceBase()));
        md.add("");
        md.add("## Plis");
        in.folds().forEach(f -> md.add("- Entraînement jusqu'au " + f.trainEnd() + ", test " + f.testFrom() + " → " + f.testEnd() + " : liens " + f.included()
                + (f.excluded().isEmpty() ? "" : " ; EXCLUS " + f.excluded()) + (f.history().isEmpty() ? "" : " ; HISTORIQUE " + f.history()) + " ; bases " + f.referenceBases() + " ; empreintes " + f.trainFingerprints()));
        for (ProfileReport p : r.profiles()) {
            md.add("");
            md.add("## Profil " + p.profile() + " — " + p.verdict().decision());
            md.add("Motif : " + String.join(" ; ", p.verdict().reasons()) + ". Plis gagnés " + p.verdict().wins() + "/" + in.folds().size()
                    + " (majorité " + p.verdict().majority() + "), médiane hors échantillon " + pct(p.verdict().medianTestGainPct()) + " % du capital, veto en test "
                    + p.verdict().vetoInTest() + ".");
            md.add("Départ : " + p.start().values());
            md.add("| Pli (fin) | Liens | Retenu | Score entr. (retenu / départ) | Plateau | Gain OOS retenu % | Gain OOS départ % | Δ pts | DD test retenu / départ % "
                    + "| Vetos test retenu | Timing pur retenu / départ % | Essais | Gagné |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (FoldResult f : p.folds()) {
                md.add("| " + f.trainEnd() + " | " + f.included() + " | " + (f.chosen() == null ? "—" : f.chosen().values()) + " | "
                        + String.format(Locale.ROOT, "%.2f / %.2f", f.trainScoreChosen(), f.trainScoreStart()) + (f.trainVetoed() ? " VETO " + f.trainVetoes() : "") + (f.trainVetoesStart().isEmpty() ? "" : " départ VETO " + f.trainVetoesStart())
                        + " | " + String.format(Locale.ROOT, "%.2f (%d/%d)%s", f.plateauScore(), f.plateauSize(), f.plateauNeighbors(), f.robust() ? " robuste" : "")
                        + " | " + pct(f.testGainChosenPct()) + " | " + pct(f.testGainStartPct()) + " | " + pct(f.deltaVsStartPts()) + " | "
                        + String.format(Locale.ROOT, "%.1f / %.1f", f.testMaxDrawdownChosenPct(), f.testMaxDrawdownStartPct()) + " | " + f.testVetoesChosen()
                        + (f.testVeto() ? " BLOQUANT" : "") + " | " + pct(f.pureTimingChosenPct()) + " / " + pct(f.pureTimingStartPct()) + " | " + f.trials()
                        + " | " + (f.won() ? "oui" : "non") + (f.foldReason() == null ? "" : " [" + f.foldDecision() + " " + f.foldReason() + "]") + (f.note() == null ? "" : " (" + f.note() + ")") + " |");
            }
            md.add("Sensibilités (effet en pts de score) : " + p.folds().stream().filter(f -> f.chosen() != null).map(f -> f.trainEnd() + " "
                    + f.sensitivities().stream().map(x -> x.name() + "=" + String.format(Locale.ROOT, "%.2f", x.effect())).toList()).toList());
            md.add("Stabilité des paramètres retenus (étendue / largeur de l'espace) : " + p.stability().stream()
                    .map(x -> x.name() + " " + x.values() + " (" + String.format(Locale.ROOT, "%.0f %%", 100 * x.rangeShare()) + ")").toList());
            md.add("Recommandé : " + (p.recommended() == null ? "aucun (garder le départ)" : p.recommended().values()));
        }
        md.add("");
        md.add("## Mesure 4.0 B — part de la pénalité de dispersion (au point de départ, actif seul)");
        r.penaltyMeasure().forEach(s -> {
            md.add("**" + s.asset() + "** : pénalité médiane " + String.format(Locale.ROOT, "%.2f", s.medianPenalty()) + " pt ; score brut médian "
                    + String.format(Locale.ROOT, "%.2f", s.medianRawScore()) + " ; part médiane du score absolu " + (s.medianShareOfAbsScore() == null ? "n/a"
                    : String.format(Locale.ROOT, "%.0f %%", 100 * s.medianShareOfAbsScore())) + " ; part médiane de l'amplitude " + String.format(Locale.ROOT, "%.0f %%",
                    100 * s.medianShareOfAmplitude()) + " ; mesures > 50 % : " + s.measuresOverAlert() + "/" + s.measures().size() + " ; alerte " + s.alert()
                    + ". " + s.proposal());
            md.add("| Profil | Pli | Gains par fenêtre % | Médiane | Dispersion | Pénalité | Score brut | Part score abs. | Part amplitude |");
            md.add("|---|---|---|---|---|---|---|---|---|");
            s.measures().forEach(m -> md.add("| " + m.profile() + " | " + m.trainEnd() + " | " + m.windowGainsPct() + " | " + pct(m.medianPct()) + " | "
                    + String.format(Locale.ROOT, "%.2f", m.dispersionPct()) + " | " + String.format(Locale.ROOT, "%.2f", m.penalty()) + " | "
                    + String.format(Locale.ROOT, "%.2f", m.rawScore()) + " | " + (m.shareOfAbsScore() == null ? "n/a" : String.format(Locale.ROOT, "%.0f %%",
                    100 * m.shareOfAbsScore())) + " | " + String.format(Locale.ROOT, "%.0f %%", 100 * m.shareOfAmplitude()) + " |"));
        });
        md.add("");
        md.add("## Temps (ms) : recherche / évaluations / ms par run");
        r.timings().folds().forEach(t -> md.add("- " + t.profile() + " " + t.trainEnd() + " : " + t.searchMs() + " / " + t.evaluationMs() + " / "
                + String.format(Locale.ROOT, "%.1f", t.msPerRun())));
        return md;
    }

    private static List<String> windowTable(String title, ScoreReport r) {
        List<String> md = new ArrayList<>();
        md.add("");
        md.add("**" + title + "** — score " + String.format(Locale.ROOT, "%.2f", r.score()) + (r.vetoed() ? " VETO " + r.vetoes() : "")
                + (r.excluded().isEmpty() ? "" : " ; exclues : " + r.excluded()));
        md.add("| Fenêtre | Du | Au | Final | DCA fixe | Gain % | DD max % | Expo moy. % | Exposition | Timing | Frais | Bloqués |");
        md.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (WindowReport w : r.windows()) {
            md.add(String.format(Locale.ROOT, "| %s | %s | %s | %.0f | %.0f | %+.2f | %.1f | %.1f | %+.0f | %+.0f | %+.0f | %d |", w.name(), w.from(),
                    w.to(), w.finalValue(), w.fixedFinalValue(), w.gainPct(), w.maxDrawdownPct(), w.avgExposurePct(), w.split().exposure(),
                    w.split().timing(), w.split().fees(), w.blockedDays()));
        }
        return md;
    }
}
