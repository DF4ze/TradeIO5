package fr.ses10doigts.tradeIO5.service.dca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.exceptions.DcaException;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.java.Log;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Suite de {@link RainbowDcaAtrRobustBenchExportTest} (2026-09-29, retour de Clem sur le premier
 * run de ce dernier) : abandonne l'idee d'un paramétrage UNIQUE cense tenir sur tous les régimes de
 * marché. Le premier run a montré exactement le problème d'un tel compromis - pour améliorer le
 * pire cas (bear), le coordinate ascent sacrifiait les fenêtres bull (score €/PnL% divisés par
 * ~40-150), un résultat inexploitable pour Clem qui prépare un indicateur de Trend séparé capable
 * de piloter le paramétrage à appliquer selon le régime détecté.
 * <p>
 * Ce bench lance donc {@link #TREND_CATEGORIES} coordinate ascents INDÉPENDANTS, un par catégorie
 * de Trend (BULL/BEAR/SIDEWAYS) - chacun avec son propre {@code aggregateScore} calculé UNIQUEMENT
 * sur les fenêtres de sa catégorie (pas de compromis inter-régime). Une catégorie avec plusieurs
 * fenêtres (BULL) les moyenne à poids égal - elles représentent le même régime, contrairement à
 * l'ancien bench qui moyennait bull/bear/sideways ensemble.
 * <p>
 * Fenêtres (cf. retour de Clem, 2026-09-29) :
 * <ul>
 *   <li>BULL : 2 fenêtres non chevauchantes (contrairement à {@code RainbowDcaAtrRobustBenchExportTest}
 *       dont les 2 fenêtres bull se recouvraient) - 12/09/2024→05/02/2025 et 06/04/2025→16/10/2025,
 *       cette dernière se terminant exactement où commence la fenêtre BEAR ci-dessous.</li>
 *   <li>BEAR : remplace {@code BEAR_2021_2022} par le bear le plus récent traversé par le marché,
 *       16/10/2025→15/08/2026 - plus pertinent qu'un cycle 2021-2022 pour calibrer le régime BEAR
 *       actuel. + 2e fenêtre bear 21/01/2025→17/04/2025 (ajoutée le 2026-09-29), les 2 optimisées
 *       INDÉPENDAMMENT comme BULL (cf. {@link TrendCategory#independentWindows()}).</li>
 *   <li>SIDEWAYS : inchangée (reprise de {@link RainbowDcaBacktestManualRunnerTest}).</li>
 * </ul>
 * Garde-fou anti-régression identique à {@link RainbowDcaAtrRobustBenchExportTest} : dans chaque
 * catégorie, une passe n'est acceptée que si elle améliore strictement l'{@code aggregateScore} du
 * point de départ.
 * <p>
 * Évolutions du 2026-09-29 (2e retour de Clem, après le 1er run réel) :
 * <ul>
 *   <li>{@code score()} pénalise désormais proportionnellement un ratio realized/(realized+potential)
 *       sous 50% (règle métier de Clem : le gain potentiel seul ne compte pas, il faut vendre).</li>
 *   <li>{@code BULL} n'agrège plus ses 2 fenêtres : chacune a son propre coordinate ascent isolé
 *       ({@link TrendCategory#independentWindows()}), pour comparer si les paramétrages retenus
 *       divergent entre les deux fenêtres bull.</li>
 * </ul>
 */
@Disabled("Analyse ponctuelle a executer par Clem (acces reseau/DB Binance requis) - reactiver au besoin")
@SpringBootTest(properties = "logging.level.fr.ses10doigts.tradeIO5=INFO")
@DisplayName("Recalibration UP - ATR + score euros, coordinate ascent multi-passes PAR CATEGORIE DE TREND")
@Log
class RainbowDcaAtrTrendBenchExportTest {

    @Autowired
    private RainbowDcaBacktestService rainbowDcaBacktestService;

    private static final BigDecimal BASE_AMOUNT = BigDecimal.valueOf(100);
    private static final String SYMBOL = "BTC";
    private static final Path OUTPUT_DIR = Path.of("target", "rainbow-dca-visualizer");
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int MAX_PASSES = 5;

    // ---- grilles "mecanisme" (identiques aux benchs precedents) ----
    private static final List<ReentryMode> L_REENTRY_MODE = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
    private static final List<BigDecimal> L_TRAILING_STOP_PERCENT = List.of(bd(3), bd(5), bd(7));
    private static final List<Integer> L_COOLDOWN_DAYS = List.of(1, 3, 7, 12);
    private static final List<Integer> L_FIXED_DELAY_DAYS = List.of(3, 5, 10, 15);
    private static final List<BigDecimal> L_SELL_FRACTION = List.of(
            new BigDecimal("0.10"), new BigDecimal("0.25"), new BigDecimal("0.33"), new BigDecimal("0.5"), new BigDecimal("0.75"));
    private static final List<Integer> L_SMA_PERIOD = List.of(10, 14, 16, 18, 20, 22, 24, 26);

    // ---- grilles ATR (identiques aux benchs precedents) ----
    private static final List<Integer> L_ATR_PERIOD = List.of(7, 10, 14, 21, 28);
    private static final List<BigDecimal> L_ATR_MULT_DOWN2 = List.of(bd(2), bd(3), bd(4), bd(5), bd(6));
    private static final List<BigDecimal> L_ATR_MULT_DOWN1 = List.of(bd("0.5"), bd(1), bd("1.5"), bd(2), bd(3));
    private static final List<BigDecimal> L_ATR_MULT_UP1 = List.of(bd("0.5"), bd(1), bd("1.5"), bd(2), bd(3));
    private static final List<BigDecimal> L_ATR_MULT_UP2 = List.of(bd(1), bd("1.5"), bd(2), bd(3), bd(4));
    private static final List<BigDecimal> L_ATR_MULT_UP3 = List.of(bd(2), bd(3), bd(4), bd(5), bd(6));

    // ---- baseline (point de depart de la passe 1, identique aux benchs precedents) ----
    private static final int BASE_ATR_PERIOD = 14;
    private static final BigDecimal BASE_ATR_MULT_DOWN2 = bd(4);
    private static final BigDecimal BASE_ATR_MULT_DOWN1 = bd(2);
    private static final BigDecimal BASE_ATR_MULT_UP1 = bd(1);
    private static final BigDecimal BASE_ATR_MULT_UP2 = bd(2);
    private static final BigDecimal BASE_ATR_MULT_UP3 = bd(4);
    private static final ReentryMode BASE_BUY_REENTRY = ReentryMode.FIXED_DELAY;
    private static final ReentryMode BASE_SELL_REENTRY = ReentryMode.FIXED_DELAY;
    private static final BigDecimal BASE_TRAILING = bd(3);
    private static final int BASE_COOLDOWN = 7;
    private static final int BASE_FIXED_DELAY = 15;
    private static final BigDecimal BASE_SELL_FRACTION = new BigDecimal("0.10");
    private static final int BASE_SMA = 20;

    private record Window(String label, LocalDate startDate, LocalDate endDate) { }

    /** Une categorie de Trend = un ou plusieurs fenetres du MEME regime, optimisees
     * independamment des autres categories (pas de compromis inter-regime, cf. javadoc classe).
     * {@code independentWindows=true} : chaque fenetre de la categorie a son PROPRE coordinate
     * ascent, sans aucune moyenne/combinaison entre elles (cf. retour de Clem 2026-09-29 sur BULL :
     * "traiter les 2 fenetres bull totalement a part... chacune son parametrage... ainsi apres je
     * peux voir si les parametrages sont tres opposes ou non"). {@code false} : une seule recherche
     * dont l'{@code aggregateScore} moyenne les fenetres (utilise pour BEAR/SIDEWAYS, 1 seule
     * fenetre chacune - la moyenne y est un no-op mais garde le meme mecanisme). */
    private record TrendCategory(String name, List<Window> windows, boolean independentWindows) { }

    private static final List<Window> BULL_WINDOWS = List.of(
            new Window("BULL_SEP24_FEV25", LocalDate.of(2024, 9, 12), LocalDate.of(2025, 2, 5)),
            new Window("BULL_AVR25_OCT25", LocalDate.of(2025, 4, 6), LocalDate.of(2025, 10, 16))
    );
    private static final List<Window> BEAR_WINDOWS = List.of(
            new Window("BEAR_OCT25_AOUT26", LocalDate.of(2025, 10, 16), LocalDate.of(2026, 8, 15)),
            new Window("BEAR_JAN25_AVR25", LocalDate.of(2025, 1, 21), LocalDate.of(2025, 4, 17))
    );
    private static final List<Window> SIDEWAYS_WINDOWS = List.of(
            new Window("SIDEWAYS", LocalDate.of(2022, 6, 20), LocalDate.of(2023, 3, 8))
    );

    private static final List<TrendCategory> TREND_CATEGORIES = List.of(
            new TrendCategory("BULL", BULL_WINDOWS, true),
            new TrendCategory("BEAR", BEAR_WINDOWS, true),
            new TrendCategory("SIDEWAYS", SIDEWAYS_WINDOWS, false)
    );

    /**
     * Jeu complet des 13 parametres optimises. Immuable : chaque passe part d'un ParamSet (le
     * candidat retenu par la passe precedente) et {@link #with} en derive des variantes pour l'OAT.
     */
    private record ParamSet(
            int atrPeriod, BigDecimal atrMultDown2, BigDecimal atrMultDown1,
            BigDecimal atrMultUp1, BigDecimal atrMultUp2, BigDecimal atrMultUp3,
            ReentryMode buyReentryMode, ReentryMode sellReentryMode, BigDecimal trailingStopPercent,
            int cooldownDays, int fixedDelayDays, BigDecimal sellFraction, int smaPeriod
    ) {
        private static ParamSet baseline() {
            return new ParamSet(BASE_ATR_PERIOD, BASE_ATR_MULT_DOWN2, BASE_ATR_MULT_DOWN1,
                    BASE_ATR_MULT_UP1, BASE_ATR_MULT_UP2, BASE_ATR_MULT_UP3,
                    BASE_BUY_REENTRY, BASE_SELL_REENTRY, BASE_TRAILING,
                    BASE_COOLDOWN, BASE_FIXED_DELAY, BASE_SELL_FRACTION, BASE_SMA);
        }

        private ParamSet with(String param, Object value) {
            return switch (param) {
                case "atrPeriod" -> new ParamSet((Integer) value, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "atrMultDown2" -> new ParamSet(atrPeriod, (BigDecimal) value, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "atrMultDown1" -> new ParamSet(atrPeriod, atrMultDown2, (BigDecimal) value, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "atrMultUp1" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, (BigDecimal) value, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "atrMultUp2" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, (BigDecimal) value, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "atrMultUp3" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, (BigDecimal) value, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "buyReentryMode" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, (ReentryMode) value, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "sellReentryMode" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, (ReentryMode) value, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "trailingStopPercent" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, (BigDecimal) value, cooldownDays, fixedDelayDays, sellFraction, smaPeriod);
                case "cooldownDays" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, (Integer) value, fixedDelayDays, sellFraction, smaPeriod);
                case "fixedDelayDays" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, (Integer) value, sellFraction, smaPeriod);
                case "sellFraction" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, (BigDecimal) value, smaPeriod);
                case "smaPeriod" -> new ParamSet(atrPeriod, atrMultDown2, atrMultDown1, atrMultUp1, atrMultUp2, atrMultUp3, buyReentryMode, sellReentryMode, trailingStopPercent, cooldownDays, fixedDelayDays, sellFraction, (Integer) value);
                default -> throw new IllegalArgumentException("Parametre inconnu : " + param);
            };
        }

        private Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("boundsMode", "ATR");
            m.put("atrPeriod", atrPeriod);
            m.put("atrMultDown2", atrMultDown2);
            m.put("atrMultDown1", atrMultDown1);
            m.put("atrMultUp1", atrMultUp1);
            m.put("atrMultUp2", atrMultUp2);
            m.put("atrMultUp3", atrMultUp3);
            m.put("buyReentryMode", buyReentryMode.name());
            m.put("sellReentryMode", sellReentryMode.name());
            m.put("trailingStopPercent", trailingStopPercent);
            m.put("cooldownDays", cooldownDays);
            m.put("fixedDelayDays", fixedDelayDays);
            m.put("sellFraction", sellFraction);
            m.put("smaPeriod", smaPeriod);
            return m;
        }
    }

    /** Une variante de l'OAT : null paramName/candidateValue == baseline de la passe courante. */
    private record ParamVariant(String key, String paramName, Object candidateValue, ParamSet params) { }

    /** Resultat complet du coordinate ascent multi-passes pour UNE categorie de Trend. */
    private record CoordinateAscentResult(
            List<Map<String, Object>> passRecords,
            ParamSet finalCandidate,
            Map<String, RainbowDcaBacktestResult> finalResultsByWindow,
            double finalAggScore,
            int passCount,
            List<ParamVariant> pass1Variants,
            Map<String, Map<String, RainbowDcaBacktestResult>> pass1ResultsByVariant
    ) { }

    @Test
    @DisplayName("ATR + score euros - un coordinate ascent independant par categorie de Trend (BULL/BEAR/SIDEWAYS)")
    void exportAtrTrendBenchDataset() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Set<Window> allWindows = new LinkedHashSet<>();
        for (TrendCategory c : TREND_CATEGORIES) { allWindows.addAll(c.windows()); }
        for (Window w : allWindows) { warmUpCache(w); }

        Map<String, Object> byTrendNode = new LinkedHashMap<>();
        for (TrendCategory category : TREND_CATEGORIES) {
            Map<String, Object> categoryNode = new LinkedHashMap<>();
            categoryNode.put("windowLabels", category.windows().stream().map(Window::label).toList());
            categoryNode.put("independentWindows", category.independentWindows());

            if (category.independentWindows()) {
                // Chaque fenetre = son PROPRE coordinate ascent isole (aucune combinaison entre
                // fenetres, cf. javadoc TrendCategory).
                Map<String, Object> perWindowNode = new LinkedHashMap<>();
                Map<String, Object> windowsNode = new LinkedHashMap<>();
                for (Window w : category.windows()) {
                    log.info("=== Categorie " + category.name() + " / fenetre " + w.label() + " (independante) : debut du coordinate ascent ===");
                    List<Window> single = List.of(w);
                    CoordinateAscentResult result = runCoordinateAscentForCategory(new TrendCategory(category.name(), single, true));
                    perWindowNode.put(w.label(), buildMultiPassNode(single, result));
                    windowsNode.put(w.label(), buildWindowNode(w, result.pass1Variants(), result.pass1ResultsByVariant(), result.finalResultsByWindow().get(w.label())));

                    double baselineAggScore = aggregateScore(single, result.pass1ResultsByVariant().get("baseline"));
                    log.info(String.format("=== Categorie %s / fenetre %s terminee : score %.4f -> %.4f apres %d passe(s) ===",
                            category.name(), w.label(), baselineAggScore, result.finalAggScore(), result.passCount()));
                }
                categoryNode.put("windows", windowsNode);
                categoryNode.put("perWindow", perWindowNode);
            } else {
                log.info("=== Categorie " + category.name() + " (" + category.windows().size() + " fenetre(s)) : debut du coordinate ascent ===");
                CoordinateAscentResult result = runCoordinateAscentForCategory(category);
                categoryNode.put("windows", buildWindowsNode(category.windows(), result.pass1Variants(), result.pass1ResultsByVariant(), result.finalResultsByWindow()));
                categoryNode.put("multiPassSearch", buildMultiPassNode(category.windows(), result));

                double baselineAggScore = aggregateScore(category.windows(), result.pass1ResultsByVariant().get("baseline"));
                log.info(String.format("=== Categorie %s terminee : aggregateScore %.4f -> %.4f apres %d passe(s) ===",
                        category.name(), baselineAggScore, result.finalAggScore(), result.passCount()));
            }
            byTrendNode.put(category.name(), categoryNode);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("symbol", SYMBOL);
        root.put("scoreFormula", "score euros = moyenne harmonique realizedGain/potentialGain, penalite proportionnelle si realized/(realized+potential) < 50% (0 au ratio 0%) ; aggregateScore = moyenne a poids egal sur les fenetres d'une categorie NON independante, sinon (BULL) un score/candidat distinct par fenetre (cf. independentWindows)");
        root.put("boundsMode", "ATR");
        root.put("searchMethod", "coordinate ascent multi-passes (max " + MAX_PASSES + " passes) INDEPENDANT par categorie de Trend, OAT+combine glouton, garde-fou anti-regression");
        root.put("byTrend", byTrendNode);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        Path outputFile = OUTPUT_DIR.resolve("atr-trend-calibration.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
        log.info("atr-trend-calibration.json exporte : " + outputFile.toAbsolutePath() + " (" + Files.size(outputFile) + " octets)");
    }

    private Map<String, Object> buildMultiPassNode(List<Window> windows, CoordinateAscentResult result) {
        Map<String, Object> multiPassNode = new LinkedHashMap<>();
        multiPassNode.put("passes", result.passRecords());
        Map<String, Object> finalCandidateNode = new LinkedHashMap<>();
        finalCandidateNode.put("parameters", result.finalCandidate().toMap());
        finalCandidateNode.put("aggregateScore", result.finalAggScore());
        finalCandidateNode.put("scoreByWindow", scoreByWindowMap(windows, result.finalResultsByWindow()));
        finalCandidateNode.put("passCount", result.passCount());
        multiPassNode.put("finalCandidate", finalCandidateNode);
        return multiPassNode;
    }

    /** Coordinate ascent multi-passes complet pour une seule categorie de Trend (cf. javadoc classe). */
    private CoordinateAscentResult runCoordinateAscentForCategory(TrendCategory category) {
        List<Window> windows = category.windows();
        ParamSet currentBase = ParamSet.baseline();
        List<ParamVariant> pass1Variants = null;
        Map<String, Map<String, RainbowDcaBacktestResult>> pass1ResultsByVariant = null;
        Map<String, RainbowDcaBacktestResult> acceptedResultsByWindow = null;
        List<Map<String, Object>> passRecords = new ArrayList<>();
        int passCount = 0;

        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            passCount = pass;
            List<ParamVariant> variants = buildVariants(currentBase);
            Map<String, Map<String, RainbowDcaBacktestResult>> resultsByVariant = runVariants(windows, variants, category.name() + " passe " + pass);
            if (pass == 1) {
                pass1Variants = variants;
                pass1ResultsByVariant = resultsByVariant;
            }

            Map<String, RainbowDcaBacktestResult> baselineResultsThisPass = resultsByVariant.get("baseline");
            double baselineAggScore = aggregateScore(windows, baselineResultsThisPass);

            Map<String, Object> bestValuePerParam = new LinkedHashMap<>();
            Map<String, Double> bestScorePerParam = new LinkedHashMap<>();
            for (ParamVariant v : variants) {
                if (v.paramName() == null) { continue; }
                double agg = aggregateScore(windows, resultsByVariant.get(v.key()));
                if (agg > baselineAggScore && agg > bestScorePerParam.getOrDefault(v.paramName(), baselineAggScore)) {
                    bestScorePerParam.put(v.paramName(), agg);
                    bestValuePerParam.put(v.paramName(), v.candidateValue());
                }
            }

            ParamSet combined = currentBase;
            for (Map.Entry<String, Object> e : bestValuePerParam.entrySet()) {
                combined = combined.with(e.getKey(), e.getValue());
            }

            Map<String, RainbowDcaBacktestResult> combinedResultsByWindow;
            double combinedAggScore;
            if (combined.equals(currentBase)) {
                combinedResultsByWindow = baselineResultsThisPass;
                combinedAggScore = baselineAggScore;
            } else {
                combinedResultsByWindow = backtestAllWindows(windows, combined);
                combinedAggScore = aggregateScore(windows, combinedResultsByWindow);
            }

            // Garde-fou : on n'accepte le candidat combine de la passe que s'il ameliore
            // strictement le score de depart de la passe (cf. javadoc classe).
            boolean improved = combinedAggScore > baselineAggScore;
            ParamSet acceptedParams = improved ? combined : currentBase;
            acceptedResultsByWindow = improved ? combinedResultsByWindow : baselineResultsThisPass;
            double acceptedAggScore = improved ? combinedAggScore : baselineAggScore;

            Map<String, Object> passRecord = new LinkedHashMap<>();
            passRecord.put("passNumber", pass);
            passRecord.put("aggregateScore", acceptedAggScore);
            passRecord.put("improved", improved);
            passRecord.put("parametersChanged", bestValuePerParam.keySet());
            passRecord.put("parameters", acceptedParams.toMap());
            passRecord.put("scoreByWindow", scoreByWindowMap(windows, acceptedResultsByWindow));
            passRecords.add(passRecord);

            log.info(String.format("%s passe %d : aggregateScore %.4f -> %.4f (%s) parametres modifies=%s",
                    category.name(), pass, baselineAggScore, acceptedAggScore, improved ? "ameliore" : "stable-rejete", bestValuePerParam.keySet()));

            currentBase = acceptedParams;
            if (!improved) { break; }
        }

        double finalAggScore = aggregateScore(windows, acceptedResultsByWindow);
        return new CoordinateAscentResult(passRecords, currentBase, acceptedResultsByWindow, finalAggScore, passCount, pass1Variants, pass1ResultsByVariant);
    }

    private void warmUpCache(Window w) {
        int maxSmaPeriod = L_SMA_PERIOD.stream().mapToInt(Integer::intValue).max().orElseThrow();
        int maxAtrPeriod = L_ATR_PERIOD.stream().mapToInt(Integer::intValue).max().orElseThrow();
        ParamSet warmup = ParamSet.baseline().with("smaPeriod", Math.max(maxSmaPeriod, maxAtrPeriod + 1));
        try {
            rainbowDcaBacktestService.backtest(buildRequest(w, warmup));
            log.info("Cache H1 pre-chauffe pour " + w.label());
        } catch (Exception e) {
            log.warning("Pre-chauffe cache echouee pour " + w.label() + " : " + e.getMessage());
        }
    }

    private RainbowDcaBacktestRequest buildRequest(Window w, ParamSet p) {
        return RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                .startDate(w.startDate()).endDate(w.endDate())
                .boundsMode(BoundsMode.ATR)
                .atrPeriod(p.atrPeriod())
                .atrMultDown2(p.atrMultDown2()).atrMultDown1(p.atrMultDown1())
                .atrMultUp1(p.atrMultUp1()).atrMultUp2(p.atrMultUp2()).atrMultUp3(p.atrMultUp3())
                .buyReentryMode(p.buyReentryMode()).sellReentryMode(p.sellReentryMode())
                .trailingStopPercent(p.trailingStopPercent())
                .cooldownDays(p.cooldownDays()).fixedDelayDays(p.fixedDelayDays())
                .sellFraction(p.sellFraction()).smaPeriod(p.smaPeriod())
                .build();
    }

    private List<ParamVariant> buildVariants(ParamSet base) {
        List<ParamVariant> variants = new ArrayList<>();
        variants.add(new ParamVariant("baseline", null, null, base));
        for (Integer v : L_ATR_PERIOD) { variants.add(new ParamVariant("atrPeriod|" + v, "atrPeriod", v, base.with("atrPeriod", v))); }
        for (BigDecimal v : L_ATR_MULT_DOWN2) { variants.add(new ParamVariant("atrMultDown2|" + v, "atrMultDown2", v, base.with("atrMultDown2", v))); }
        for (BigDecimal v : L_ATR_MULT_DOWN1) { variants.add(new ParamVariant("atrMultDown1|" + v, "atrMultDown1", v, base.with("atrMultDown1", v))); }
        for (BigDecimal v : L_ATR_MULT_UP1) { variants.add(new ParamVariant("atrMultUp1|" + v, "atrMultUp1", v, base.with("atrMultUp1", v))); }
        for (BigDecimal v : L_ATR_MULT_UP2) { variants.add(new ParamVariant("atrMultUp2|" + v, "atrMultUp2", v, base.with("atrMultUp2", v))); }
        for (BigDecimal v : L_ATR_MULT_UP3) { variants.add(new ParamVariant("atrMultUp3|" + v, "atrMultUp3", v, base.with("atrMultUp3", v))); }
        for (ReentryMode v : L_REENTRY_MODE) { variants.add(new ParamVariant("buyReentryMode|" + v, "buyReentryMode", v, base.with("buyReentryMode", v))); }
        for (ReentryMode v : L_REENTRY_MODE) { variants.add(new ParamVariant("sellReentryMode|" + v, "sellReentryMode", v, base.with("sellReentryMode", v))); }
        for (BigDecimal v : L_TRAILING_STOP_PERCENT) { variants.add(new ParamVariant("trailingStopPercent|" + v, "trailingStopPercent", v, base.with("trailingStopPercent", v))); }
        for (Integer v : L_COOLDOWN_DAYS) { variants.add(new ParamVariant("cooldownDays|" + v, "cooldownDays", v, base.with("cooldownDays", v))); }
        for (Integer v : L_FIXED_DELAY_DAYS) { variants.add(new ParamVariant("fixedDelayDays|" + v, "fixedDelayDays", v, base.with("fixedDelayDays", v))); }
        for (BigDecimal v : L_SELL_FRACTION) { variants.add(new ParamVariant("sellFraction|" + v, "sellFraction", v, base.with("sellFraction", v))); }
        for (Integer v : L_SMA_PERIOD) { variants.add(new ParamVariant("smaPeriod|" + v, "smaPeriod", v, base.with("smaPeriod", v))); }
        return variants;
    }

    /** Lance TOUTES les variantes sur TOUTES les fenetres de la categorie en parallele (un seul executor par passe). */
    private Map<String, Map<String, RainbowDcaBacktestResult>> runVariants(List<Window> windows, List<ParamVariant> variants, String label) {
        Map<String, Map<String, RainbowDcaBacktestResult>> resultsByVariant = new ConcurrentHashMap<>();
        for (ParamVariant v : variants) { resultsByVariant.put(v.key(), new ConcurrentHashMap<>()); }
        List<Callable<Void>> tasks = new ArrayList<>();
        for (ParamVariant v : variants) {
            for (Window w : windows) {
                tasks.add(() -> {
                    // Bornes ATR non monotones (2026-09-30, cf. RainbowDcaBacktestService#validate) :
                    // un variant de coordinate ascent peut rendre les bornes invalides (ex. up3 <
                    // up2 courant) sans que ce soit un bug — on l'ecarte juste de la selection (null
                    // -> score() renvoie -Infinity, cf. plus bas) au lieu de planter tout le bench.
                    try {
                        RainbowDcaBacktestResult r = rainbowDcaBacktestService.backtest(buildRequest(w, v.params()));
                        resultsByVariant.get(v.key()).put(w.label(), r);
                    } catch (DcaException e) {
                        // pas de put() : ConcurrentHashMap n'accepte pas les valeurs null, et une
                        // cle absente produit deja null au get() cote score()/aggregateScore().
                    }
                    return null;
                });
            }
        }
        runAll(tasks, label);
        return resultsByVariant;
    }

    private Map<String, RainbowDcaBacktestResult> backtestAllWindows(List<Window> windows, ParamSet p) {
        Map<String, RainbowDcaBacktestResult> results = new ConcurrentHashMap<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Window w : windows) {
            tasks.add(() -> {
                // Meme garde-fou que runVariants() : le candidat combine (issu de bests deja
                // valides individuellement, mais combines ensemble) peut redevenir non monotone.
                try {
                    RainbowDcaBacktestResult r = rainbowDcaBacktestService.backtest(buildRequest(w, p));
                    results.put(w.label(), r);
                } catch (DcaException e) {
                    // idem runVariants() : pas de put() null (ConcurrentHashMap l'interdit).
                }
                return null;
            });
        }
        runAll(tasks, "candidat combine");
        return results;
    }

    private void runAll(List<Callable<Void>> tasks, String label) {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) { future.get(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant l'export de " + label, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Echec inattendu pendant l'export de " + label, e.getCause());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Critere de selection : moyenne a POIDS EGAL des scores € harmoniques des fenetres de la
     * categorie (une seule fenetre pour BEAR/SIDEWAYS -> revient a son propre score ; 2 fenetres
     * pour BULL -> moyenne simple, elles representent le meme regime donc pas de raison de
     * privilegier l'une sur l'autre). Le score par fenetre individuelle (methode {@link #score})
     * reste calcule et exporte en detail.
     */
    private double aggregateScore(List<Window> windows, Map<String, RainbowDcaBacktestResult> resultsByWindow) {
        double sum = 0;
        for (Window w : windows) { sum += score(resultsByWindow.get(w.label())); }
        return round(sum / windows.size());
    }

    private Map<String, Object> scoreByWindowMap(List<Window> windows, Map<String, RainbowDcaBacktestResult> resultsByWindow) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Window w : windows) { m.put(w.label(), score(resultsByWindow.get(w.label()))); }
        return m;
    }

    /** Seuil de Clem (2026-09-29) : au moins 50% du gain (realise+potentiel) doit etre REALISE,
     * sinon le "gain potentiel" ne sert a rien si un retournement de marche l'efface. */
    private static final double MIN_REALIZED_RATIO = 0.5;

    /**
     * Moyenne HARMONIQUE (pas arithmetique) de realized/potential en euros pour UNE fenetre :
     * penalise durement le cas "0 vente" (score = min(realized,potential)) au lieu de le
     * recompenser comme la moyenne arithmetique le faisait.
     * <p>
     * Depuis 2026-09-29 (retour de Clem : "je voulais a minima 50% de PnL reel pour 50% de PnL
     * potentiel") : penalite PROPORTIONNELLE quand realized/(realized+potential) < {@link
     * #MIN_REALIZED_RATIO} - le score harmonique est multiplie par ce ratio rapporte au seuil
     * (1.0 au seuil, 0 a 0% realise), au lieu d'un cutoff brutal. Le cas "0 vente" (branche
     * ci-dessus) reste au plancher min(realized,potential), deja plus severe que cette penalite.
     */
    /** Sentinel FINI (round()/BigDecimal ne supportent pas Infinity/NaN) mais largement plus bas
     * que n'importe quel score reel atteignable (gains/pertes en euros sur un backtest BTC) : une
     * combinaison rejetee ne peut donc jamais etre choisie comme meilleure variante. */
    private static final double INVALID_SCORE = -1.0E12;

    private double score(RainbowDcaBacktestResult r) {
        // r == null : combinaison de bornes ATR/% rejetee par RainbowDcaBacktestService#validate()
        // (non monotone) — cf. runVariants()/backtestAllWindows().
        if (r == null) {
            return INVALID_SCORE;
        }
        double realized = r.getRealizedGain() != null ? r.getRealizedGain().doubleValue() : 0.0;
        double potential = r.getPotentialGain() != null ? r.getPotentialGain().doubleValue() : 0.0;
        if (realized <= 0 || potential <= 0) {
            return round(Math.min(realized, potential));
        }
        double harmonicMean = 2 * realized * potential / (realized + potential);
        double realizedRatio = realized / (realized + potential);
        if (realizedRatio < MIN_REALIZED_RATIO) {
            harmonicMean *= realizedRatio / MIN_REALIZED_RATIO;
        }
        return round(harmonicMean);
    }

    private Map<String, Object> buildWindowsNode(
            List<Window> windows,
            List<ParamVariant> pass1Variants,
            Map<String, Map<String, RainbowDcaBacktestResult>> pass1ResultsByVariant,
            Map<String, RainbowDcaBacktestResult> finalResultsByWindow) {
        Map<String, Object> windowsNode = new LinkedHashMap<>();
        for (Window w : windows) {
            windowsNode.put(w.label(), buildWindowNode(w, pass1Variants, pass1ResultsByVariant, finalResultsByWindow.get(w.label())));
        }
        return windowsNode;
    }

    /**
     * Node par fenetre, meme format que RainbowDcaAtrRobustBenchExportTest : baseline/variations/
     * topByScore proviennent de l'OAT de la PASSE 1 (sensibilite autour du point de depart) ;
     * combinedCandidate = candidat final du coordinate ascent de LA CATEGORIE, evalue sur cette
     * fenetre specifiquement.
     */
    private Map<String, Object> buildWindowNode(
            Window w, List<ParamVariant> pass1Variants,
            Map<String, Map<String, RainbowDcaBacktestResult>> pass1ResultsByVariant,
            RainbowDcaBacktestResult finalResult) {
        Map<String, Object> windowNode = new LinkedHashMap<>();
        windowNode.put("window", Map.of("label", w.label(), "startDate", w.startDate(), "endDate", w.endDate()));

        RainbowDcaBacktestResult baselineResult = pass1ResultsByVariant.get("baseline").get(w.label());
        if (baselineResult == null) {
            // Ne doit jamais arriver : BASE_ATR_MULT_* (le point de depart fixe du coordinate
            // ascent) doit toujours respecter la monotonie des bornes (cf. RainbowDcaBacktestService#validate()).
            // Erreur explicite plutot qu'une NPE opaque plus loin si ce garde-fou est un jour viole.
            throw new IllegalStateException("baseline invalide (bornes ATR non monotones ?) pour la fenetre "
                    + w.label() + " — verifier BASE_ATR_MULT_DOWN2/DOWN1/UP1/UP2/UP3");
        }
        windowNode.put("baseline", buildEntryNode(baselineResult, score(baselineResult)));

        Map<String, List<Map<String, Object>>> variationsNode = new LinkedHashMap<>();
        List<Map<String, Object>> allScored = new ArrayList<>();
        allScored.add(withParam(buildEntryNode(baselineResult, score(baselineResult)), "baseline", "baseline"));

        for (ParamVariant v : pass1Variants) {
            if (v.paramName() == null) { continue; }
            RainbowDcaBacktestResult result = pass1ResultsByVariant.get(v.key()).get(w.label());
            double s = score(result);
            Map<String, Object> entry = buildEntryNode(result, s);
            entry.put("value", v.candidateValue());
            variationsNode.computeIfAbsent(v.paramName(), k -> new ArrayList<>()).add(entry);
            allScored.add(withParam(entry, v.paramName(), v.candidateValue()));
        }
        windowNode.put("variations", variationsNode);
        windowNode.put("variationsAuditPass", 1);

        allScored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("score")).reversed());
        windowNode.put("topByScore", allScored.subList(0, Math.min(15, allScored.size())));

        Map<String, Object> combinedNode = buildEntryNode(finalResult, score(finalResult));
        combinedNode.put("source", "finalCandidate du coordinate ascent de cette fenetre/categorie (cf. multiPassSearch.finalCandidate, ou perWindow.<label>.finalCandidate si independentWindows=true)");
        windowNode.put("combinedCandidate", combinedNode);

        return windowNode;
    }

    private Map<String, Object> withParam(Map<String, Object> entry, String paramName, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(entry);
        m.put("param", paramName);
        m.put("candidateValue", String.valueOf(value));
        return m;
    }

    private Map<String, Object> buildEntryNode(RainbowDcaBacktestResult r, double s) {
        Map<String, Object> entry = new LinkedHashMap<>();
        // r == null : combinaison de bornes ATR/% rejetee par RainbowDcaBacktestService#validate()
        // (non monotone, cf. score()/INVALID_SCORE) — pas de parameters/summary/occurrences
        // disponibles (le backtest n'a jamais tourne), mais on le dit explicitement dans l'export
        // plutot que de planter ou de le passer sous silence.
        if (r == null) {
            entry.put("invalid", true);
            entry.put("invalidReason", "bornes ATR/% rejetees par RainbowDcaBacktestService#validate() (non monotones)");
            entry.put("score", s);
            return entry;
        }
        entry.put("parameters", parametersToMap(r.getParameters()));
        entry.put("score", s);
        entry.put("summary", buildSummaryNode(r));
        entry.put("occurrences", buildOccurrencesNode(r.getOccurrences()));
        return entry;
    }

    private Map<String, Object> buildSummaryNode(RainbowDcaBacktestResult r) {
        DcaResult fixed = r.getFixedDcaComparison();
        BigDecimal deltaPnlPercent = (r.getPnlPercent() != null && fixed.getPnlPercent() != null)
                ? r.getPnlPercent().subtract(fixed.getPnlPercent())
                : null;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalInvested", r.getTotalInvested());
        summary.put("buyTriggeredCount", r.getBuyTriggeredCount());
        summary.put("totalSaleProceeds", r.getTotalSaleProceeds());
        summary.put("sellTriggeredCount", r.getSellTriggeredCount());
        summary.put("currentValue", r.getCurrentValue());
        summary.put("pnl", r.getPnl());
        summary.put("pnlPercent", r.getPnlPercent());
        summary.put("realizedGain", r.getRealizedGain());
        summary.put("realizedGainPercent", r.getRealizedGainPercent());
        summary.put("potentialGain", r.getPotentialGain());
        summary.put("potentialGainPercent", r.getPotentialGainPercent());
        Map<String, Object> fixedDca = new LinkedHashMap<>();
        fixedDca.put("totalInvested", fixed.getTotalInvested());
        fixedDca.put("pnl", fixed.getPnl());
        fixedDca.put("pnlPercent", fixed.getPnlPercent());
        summary.put("fixedDca", fixedDca);
        summary.put("deltaVsFixedPnlPercent", deltaPnlPercent);
        return summary;
    }

    private List<Map<String, Object>> buildOccurrencesNode(List<RainbowDcaOccurrence> occurrences) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RainbowDcaOccurrence o : occurrences) {
            if (o.getBuyAction() == RainbowDcaOccurrence.BuyAction.TRIGGERED) {
                out.add(occurrenceEntry(o, "BUY_TRIGGERED"));
            }
            if (o.getSellAction() == RainbowDcaOccurrence.SellAction.TRIGGERED) {
                out.add(occurrenceEntry(o, "SELL_TRIGGERED"));
            }
        }
        return out;
    }

    private Map<String, Object> occurrenceEntry(RainbowDcaOccurrence o, String type) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("date", o.getDate());
        entry.put("price", o.getClose());
        entry.put("type", type);
        if ("BUY_TRIGGERED".equals(type)) {
            entry.put("amountInvested", o.getAmountInvested());
            entry.put("quantityBought", o.getQuantityBought());
        } else {
            entry.put("quantitySold", o.getQuantitySold());
            entry.put("saleProceeds", o.getSaleProceeds());
        }
        return entry;
    }

    private Map<String, Object> parametersToMap(RainbowDcaBacktestRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("boundsMode", r.getBoundsMode().name());
        m.put("atrPeriod", r.getAtrPeriod());
        m.put("atrMultDown2", r.getAtrMultDown2());
        m.put("atrMultDown1", r.getAtrMultDown1());
        m.put("atrMultUp1", r.getAtrMultUp1());
        m.put("atrMultUp2", r.getAtrMultUp2());
        m.put("atrMultUp3", r.getAtrMultUp3());
        m.put("buyReentryMode", r.getBuyReentryMode().name());
        m.put("sellReentryMode", r.getSellReentryMode().name());
        m.put("trailingStopPercent", r.getTrailingStopBuyPercent()); // benches : achat = vente (valeur unique cherchée)
        m.put("trailingStopSellPercent", r.getTrailingStopSellPercent());
        m.put("cooldownDays", r.getCooldownDays());
        m.put("fixedDelayDays", r.getFixedDelayDays());
        m.put("sellFraction", r.getSellFraction());
        m.put("smaPeriod", r.getSmaPeriod());
        return m;
    }

    private static BigDecimal bd(int value) { return BigDecimal.valueOf(value); }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    private static double round(double v) {
        return new BigDecimal(v, new MathContext(6)).doubleValue();
    }

    @TestConfiguration
    static class MemoizingDcaCalculatorServiceConfig {
        @Bean
        @Primary
        DcaCalculatorService memoizingDcaCalculatorService(
                @Qualifier("cachingBinanceMarketDataApiClient") MarketDataApiClient binanceClient,
                @Qualifier("cachingKrakenMarketDataApiClient") MarketDataApiClient krakenClient,
                @Qualifier("cachingOkxMarketDataApiClient") MarketDataApiClient okxClient,
                DomainClock clock,
                AssetProviderRepository assetProviderRepository
        ) {
            return new DcaCalculatorService(binanceClient, krakenClient, okxClient, clock, assetProviderRepository) {
                private final Map<CalculateKey, DcaResult> cache = new ConcurrentHashMap<>();
                @Override
                public DcaResult calculate(
                        String symbol, LocalDate startDate, LocalDate endDate, TimeFrame frequency,
                        int purchaseHourUtc, BigDecimal amount, BigDecimal feePercent, MarketDataSource source,
                        Instant valuationInstant
                ) {
                    CalculateKey key = new CalculateKey(
                            symbol, startDate, endDate, frequency, purchaseHourUtc, amount, feePercent, source, valuationInstant);
                    return cache.computeIfAbsent(key, k -> super.calculate(
                            symbol, startDate, endDate, frequency, purchaseHourUtc, amount, feePercent, source, valuationInstant));
                }
            };
        }
        private record CalculateKey(
                String symbol, LocalDate startDate, LocalDate endDate, TimeFrame frequency,
                int purchaseHourUtc, BigDecimal amount, BigDecimal feePercent, MarketDataSource source,
                Instant valuationInstant
        ) { }
    }
}
