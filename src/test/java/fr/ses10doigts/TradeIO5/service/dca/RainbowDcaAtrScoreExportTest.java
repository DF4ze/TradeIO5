package fr.ses10doigts.tradeIO5.service.dca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Suite de RainbowDcaBullScoreExportTest (2026-09-26) : Clem a identifie 2 corrections a
 * apporter avant de refaire un bench utile -
 *  1) score en EUROS absolus (0.5*realizedGain€ + 0.5*potentialGain€), plus en %, pour eviter
 *     l'artefact "moins acheter = plus de %" ;
 *  2) bornes ATR-relatives (BoundsMode.ATR) plutot que %fixe-au-dessus-de-SMA, pour s'adapter
 *     au regime de volatilite courant (grinding bull 2025 vs spike bull 2023-24).
 * Exporte un dataset OAT (12 nouveaux parametres : atrPeriod + 5 atrMult + les parametres
 * "mecanisme" deja connus) + candidat combine, sur les 2 memes fenetres bull que le bench
 * precedent, pour le visualiseur.
 */
@Disabled("Analyse ponctuelle deja executee - reactiver au besoin pour recalculer")
@SpringBootTest(properties = "logging.level.fr.ses10doigts.tradeIO5=INFO")
@DisplayName("Recalibration UP - bornes ATR + score euros sur 2 fenetres bull recentes")
@Log
class RainbowDcaAtrScoreExportTest {

    @Autowired
    private RainbowDcaBacktestService rainbowDcaBacktestService;

    private static final BigDecimal BASE_AMOUNT = BigDecimal.valueOf(100);
    private static final String SYMBOL = "BTC";
    private static final Path OUTPUT_DIR = Path.of("target", "rainbow-dca-visualizer");
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());

    // ---- grilles "mecanisme" (identiques aux benchs precedents) ----
    private static final List<ReentryMode> L_REENTRY_MODE = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
    private static final List<BigDecimal> L_TRAILING_STOP_PERCENT = List.of(bd(3), bd(5), bd(7));
    private static final List<Integer> L_COOLDOWN_DAYS = List.of(1, 3, 7, 12);
    private static final List<Integer> L_FIXED_DELAY_DAYS = List.of(3, 5, 10, 15);
    private static final List<BigDecimal> L_SELL_FRACTION = List.of(
            new BigDecimal("0.10"), new BigDecimal("0.25"), new BigDecimal("0.33"), new BigDecimal("0.5"), new BigDecimal("0.75"));
    private static final List<Integer> L_SMA_PERIOD = List.of(10, 14, 16, 18, 20, 22, 24, 26);

    // ---- grilles ATR (nouvelles) ----
    private static final List<Integer> L_ATR_PERIOD = List.of(7, 10, 14, 21, 28);
    private static final List<BigDecimal> L_ATR_MULT_DOWN2 = List.of(bd(2), bd(3), bd(4), bd(5), bd(6));
    private static final List<BigDecimal> L_ATR_MULT_DOWN1 = List.of(bd("0.5"), bd(1), bd("1.5"), bd(2), bd(3));
    private static final List<BigDecimal> L_ATR_MULT_UP1 = List.of(bd("0.5"), bd(1), bd("1.5"), bd(2), bd(3));
    private static final List<BigDecimal> L_ATR_MULT_UP2 = List.of(bd(1), bd("1.5"), bd(2), bd(3), bd(4));
    private static final List<BigDecimal> L_ATR_MULT_UP3 = List.of(bd(2), bd(3), bd(4), bd(5), bd(6));

    // ---- baseline ATR : point de depart raisonnable, coherent avec les defaults de BoundsMode.ATR ----
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

    private static final List<Window> WINDOWS = List.of(
            new Window("MARS25_OCT25", LocalDate.of(2025, 3, 14), LocalDate.of(2025, 10, 24)),
            new Window("AOUT24_OCT25", LocalDate.of(2024, 8, 24), LocalDate.of(2025, 10, 24))
    );

    @Test
    @DisplayName("ATR + score euros - OAT + candidat combine sur les 2 fenetres")
    void exportAtrScoreDataset() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        for (Window w : WINDOWS) { warmUpCache(w); }
        Map<String, Object> windowsNode = new LinkedHashMap<>();
        for (Window w : WINDOWS) { windowsNode.put(w.label(), buildWindowNode(w)); }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("symbol", SYMBOL);
        root.put("scoreFormula", "0.5 * realizedGain(euros) + 0.5 * potentialGain(euros)");
        root.put("boundsMode", "ATR");
        root.put("windows", windowsNode);
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        Path outputFile = OUTPUT_DIR.resolve("atr-score-calibration.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
        log.info("atr-score-calibration.json exporte : " + outputFile.toAbsolutePath() + " (" + Files.size(outputFile) + " octets)");
    }

    private void warmUpCache(Window w) {
        int maxSmaPeriod = L_SMA_PERIOD.stream().mapToInt(Integer::intValue).max().orElseThrow();
        int maxAtrPeriod = L_ATR_PERIOD.stream().mapToInt(Integer::intValue).max().orElseThrow();
        try {
            rainbowDcaBacktestService.backtest(baseRequestBuilder(w).smaPeriod(Math.max(maxSmaPeriod, maxAtrPeriod + 1)).build());
            log.info("Cache H1 pre-chauffe pour " + w.label());
        } catch (Exception e) {
            log.warning("Pre-chauffe cache echouee pour " + w.label() + " : " + e.getMessage());
        }
    }

    private RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder baseRequestBuilder(Window w) {
        return RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                .startDate(w.startDate()).endDate(w.endDate())
                .boundsMode(BoundsMode.ATR)
                .atrPeriod(BASE_ATR_PERIOD)
                .atrMultDown2(BASE_ATR_MULT_DOWN2).atrMultDown1(BASE_ATR_MULT_DOWN1)
                .atrMultUp1(BASE_ATR_MULT_UP1).atrMultUp2(BASE_ATR_MULT_UP2).atrMultUp3(BASE_ATR_MULT_UP3)
                .buyReentryMode(BASE_BUY_REENTRY).sellReentryMode(BASE_SELL_REENTRY)
                .trailingStopPercent(BASE_TRAILING)
                .cooldownDays(BASE_COOLDOWN).fixedDelayDays(BASE_FIXED_DELAY)
                .sellFraction(BASE_SELL_FRACTION).smaPeriod(BASE_SMA);
    }

    private record VariationJob(String paramName, Object candidateValue, RainbowDcaBacktestRequest request) { }

    private Map<String, Object> buildWindowNode(Window w) {
        List<VariationJob> jobs = buildVariationJobs(w);
        Map<String, RainbowDcaBacktestResult> resultsByKey = new ConcurrentHashMap<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        tasks.add(() -> {
            resultsByKey.put("baseline", rainbowDcaBacktestService.backtest(baseRequestBuilder(w).build()));
            return null;
        });
        for (VariationJob job : jobs) {
            tasks.add(() -> {
                resultsByKey.put(job.paramName() + "|" + job.candidateValue(), rainbowDcaBacktestService.backtest(job.request()));
                return null;
            });
        }
        runAll(tasks, w.label());

        Map<String, Object> windowNode = new LinkedHashMap<>();
        windowNode.put("window", Map.of("label", w.label(), "startDate", w.startDate(), "endDate", w.endDate()));

        RainbowDcaBacktestResult baselineResult = resultsByKey.get("baseline");
        windowNode.put("baseline", buildEntryNode(baselineResult, score(baselineResult)));

        Map<String, List<Map<String, Object>>> variationsNode = new LinkedHashMap<>();
        List<Map<String, Object>> allScored = new ArrayList<>();
        allScored.add(withParam(buildEntryNode(baselineResult, score(baselineResult)), "baseline", "baseline"));

        Map<String, Object> bestValuePerParam = new LinkedHashMap<>();
        Map<String, Double> bestScorePerParam = new LinkedHashMap<>();

        for (VariationJob job : jobs) {
            RainbowDcaBacktestResult result = resultsByKey.get(job.paramName() + "|" + job.candidateValue());
            double s = score(result);
            Map<String, Object> entry = buildEntryNode(result, s);
            entry.put("value", job.candidateValue());
            variationsNode.computeIfAbsent(job.paramName(), k -> new ArrayList<>()).add(entry);
            allScored.add(withParam(entry, job.paramName(), job.candidateValue()));

            double baselineScore = score(baselineResult);
            if (s > baselineScore && s > bestScorePerParam.getOrDefault(job.paramName(), baselineScore)) {
                bestScorePerParam.put(job.paramName(), s);
                bestValuePerParam.put(job.paramName(), job.candidateValue());
            }
        }
        windowNode.put("variations", variationsNode);

        allScored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("score")).reversed());
        windowNode.put("topByScore", allScored.subList(0, Math.min(15, allScored.size())));

        RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder combinedBuilder = baseRequestBuilder(w);
        Map<String, Object> appliedOverrides = new LinkedHashMap<>();
        applyIfPresent(bestValuePerParam, "atrPeriod", (Integer v) -> combinedBuilder.atrPeriod(v), appliedOverrides, Integer.class);
        applyIfPresent(bestValuePerParam, "atrMultDown2", (BigDecimal v) -> combinedBuilder.atrMultDown2(v), appliedOverrides, BigDecimal.class);
        applyIfPresent(bestValuePerParam, "atrMultDown1", (BigDecimal v) -> combinedBuilder.atrMultDown1(v), appliedOverrides, BigDecimal.class);
        applyIfPresent(bestValuePerParam, "atrMultUp1", (BigDecimal v) -> combinedBuilder.atrMultUp1(v), appliedOverrides, BigDecimal.class);
        applyIfPresent(bestValuePerParam, "atrMultUp2", (BigDecimal v) -> combinedBuilder.atrMultUp2(v), appliedOverrides, BigDecimal.class);
        applyIfPresent(bestValuePerParam, "atrMultUp3", (BigDecimal v) -> combinedBuilder.atrMultUp3(v), appliedOverrides, BigDecimal.class);
        if (bestValuePerParam.containsKey("buyReentryMode")) {
            ReentryMode v = ReentryMode.valueOf((String) bestValuePerParam.get("buyReentryMode"));
            combinedBuilder.buyReentryMode(v);
            appliedOverrides.put("buyReentryMode", v.name());
        }
        if (bestValuePerParam.containsKey("sellReentryMode")) {
            ReentryMode v = ReentryMode.valueOf((String) bestValuePerParam.get("sellReentryMode"));
            combinedBuilder.sellReentryMode(v);
            appliedOverrides.put("sellReentryMode", v.name());
        }
        applyIfPresent(bestValuePerParam, "trailingStopPercent", (BigDecimal v) -> combinedBuilder.trailingStopPercent(v), appliedOverrides, BigDecimal.class);
        applyIfPresent(bestValuePerParam, "cooldownDays", (Integer v) -> combinedBuilder.cooldownDays(v), appliedOverrides, Integer.class);
        applyIfPresent(bestValuePerParam, "fixedDelayDays", (Integer v) -> combinedBuilder.fixedDelayDays(v), appliedOverrides, Integer.class);
        applyIfPresent(bestValuePerParam, "sellFraction", (BigDecimal v) -> combinedBuilder.sellFraction(v), appliedOverrides, BigDecimal.class);
        applyIfPresent(bestValuePerParam, "smaPeriod", (Integer v) -> combinedBuilder.smaPeriod(v), appliedOverrides, Integer.class);

        RainbowDcaBacktestResult combinedResult = rainbowDcaBacktestService.backtest(combinedBuilder.build());
        Map<String, Object> combinedNode = buildEntryNode(combinedResult, score(combinedResult));
        combinedNode.put("appliedOverrides", appliedOverrides);
        windowNode.put("combinedCandidate", combinedNode);

        log.info("fenetre " + w.label() + " : " + (1 + jobs.size() + 1) + " backtests values, score baseline="
                + round(score(baselineResult)) + ", score candidat combine=" + round(score(combinedResult)));
        return windowNode;
    }

    @SuppressWarnings("unchecked")
    private <T> void applyIfPresent(Map<String, Object> bestValuePerParam, String key, java.util.function.Consumer<T> setter,
                                     Map<String, Object> appliedOverrides, Class<T> type) {
        if (bestValuePerParam.containsKey(key)) {
            T v = (T) bestValuePerParam.get(key);
            setter.accept(v);
            appliedOverrides.put(key, v);
        }
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

    private Map<String, Object> withParam(Map<String, Object> entry, String paramName, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(entry);
        m.put("param", paramName);
        m.put("candidateValue", String.valueOf(value));
        return m;
    }

    private List<VariationJob> buildVariationJobs(Window w) {
        List<VariationJob> jobs = new ArrayList<>();
        for (Integer v : L_ATR_PERIOD) {
            jobs.add(new VariationJob("atrPeriod", v, baseRequestBuilder(w).atrPeriod(v).build()));
        }
        for (BigDecimal v : L_ATR_MULT_DOWN2) {
            jobs.add(new VariationJob("atrMultDown2", v, baseRequestBuilder(w).atrMultDown2(v).build()));
        }
        for (BigDecimal v : L_ATR_MULT_DOWN1) {
            jobs.add(new VariationJob("atrMultDown1", v, baseRequestBuilder(w).atrMultDown1(v).build()));
        }
        for (BigDecimal v : L_ATR_MULT_UP1) {
            jobs.add(new VariationJob("atrMultUp1", v, baseRequestBuilder(w).atrMultUp1(v).build()));
        }
        for (BigDecimal v : L_ATR_MULT_UP2) {
            jobs.add(new VariationJob("atrMultUp2", v, baseRequestBuilder(w).atrMultUp2(v).build()));
        }
        for (BigDecimal v : L_ATR_MULT_UP3) {
            jobs.add(new VariationJob("atrMultUp3", v, baseRequestBuilder(w).atrMultUp3(v).build()));
        }
        for (ReentryMode v : L_REENTRY_MODE) {
            jobs.add(new VariationJob("buyReentryMode", v.name(), baseRequestBuilder(w).buyReentryMode(v).build()));
        }
        for (ReentryMode v : L_REENTRY_MODE) {
            jobs.add(new VariationJob("sellReentryMode", v.name(), baseRequestBuilder(w).sellReentryMode(v).build()));
        }
        for (BigDecimal v : L_TRAILING_STOP_PERCENT) {
            jobs.add(new VariationJob("trailingStopPercent", v, baseRequestBuilder(w).trailingStopPercent(v).build()));
        }
        for (Integer v : L_COOLDOWN_DAYS) {
            jobs.add(new VariationJob("cooldownDays", v, baseRequestBuilder(w).cooldownDays(v).build()));
        }
        for (Integer v : L_FIXED_DELAY_DAYS) {
            jobs.add(new VariationJob("fixedDelayDays", v, baseRequestBuilder(w).fixedDelayDays(v).build()));
        }
        for (BigDecimal v : L_SELL_FRACTION) {
            jobs.add(new VariationJob("sellFraction", v, baseRequestBuilder(w).sellFraction(v).build()));
        }
        for (Integer v : L_SMA_PERIOD) {
            jobs.add(new VariationJob("smaPeriod", v, baseRequestBuilder(w).smaPeriod(v).build()));
        }
        return jobs;
    }

    /**
     * Moyenne HARMONIQUE (pas arithmetique) de realized/potential en euros : penalise durement
     * le cas "0 vente" (score = min(realized,potential), typiquement 0) au lieu de le recompenser
     * comme la moyenne arithmetique le faisait (un potentiel enorme + 0 realise gagnait quand meme,
     * cf. l'analyse du 2026-09-27 - toute fenetre qui se termine haut favorise "ne rien vendre").
     * Force un vrai equilibre 50/50 realise/potentiel plutot qu'une simple somme ponderee.
     */
    private double score(RainbowDcaBacktestResult r) {
        double realized = r.getRealizedGain() != null ? r.getRealizedGain().doubleValue() : 0.0;
        double potential = r.getPotentialGain() != null ? r.getPotentialGain().doubleValue() : 0.0;
        if (realized <= 0 || potential <= 0) {
            return round(Math.min(realized, potential));
        }
        return round(2 * realized * potential / (realized + potential));
    }

    private Map<String, Object> buildEntryNode(RainbowDcaBacktestResult r, double s) {
        Map<String, Object> entry = new LinkedHashMap<>();
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
