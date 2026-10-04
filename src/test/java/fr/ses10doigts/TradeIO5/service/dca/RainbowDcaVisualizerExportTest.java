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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runner manuel (réseau + DB réels) qui précalcule et exporte {@code target/rainbow-dca-visualizer/dataset.json},
 * consommé par le visualiseur HTML autonome {@code docs/visualiseur-rainbow-dca.html} — cf.
 * docs/prompts/prompt-implementation-visualiseur-rainbow-dca-chart.md.
 * <p>
 * Pour chacun des 3 presets UP/DOWN/RANGE (mêmes fenêtres et mêmes valeurs stabilisées que
 * {@link RainbowDcaBacktestManualRunnerTest}, cf. tableau du prompt) :
 * <ul>
 *     <li>un backtest "baseline" avec les paramètres du preset tels quels ;</li>
 *     <li>une sensibilité "un paramètre à la fois" (OAT, PAS de grille complète — cross-product
 *     injouable, cf. point 1 du prompt) sur les 12 paramètres concernés (5 bornes %, 6 reentry,
 *     1 SMA), chaque valeur candidate étant une des valeurs déjà présentes dans
 *     {@link RainbowDcaBacktestManualRunnerTest#boundsGrid()} / {@code reentryGrid()} /
 *     {@code smaGrid()} (grilles dupliquées ici en constantes, pas de dépendance de test à test) ;
 *     un jeu de bornes candidat qui casserait l'ordre {@code down2 < down1 < up1 < up2 < up3} est
 *     silencieusement omis du dataset (pas d'entrée), plutôt que de produire un backtest invalide.</li>
 * </ul>
 * Chaque entrée du dataset embarque les paramètres complets utilisés, le résumé chiffré exact des
 * champs déjà loggés par le runner de calibration (§25/§26 de l'étude) et la liste des occurrences
 * "achat x3 déclenché" / "vente déclenchée" (jamais les achats de zone simples, cf. Ce que ce lot
 * EST du prompt) pour positionnement des marqueurs sur le graphique.
 * <p>
 * Désactivé par défaut ({@link Disabled}), même convention que {@link RainbowDcaBacktestManualRunnerTest} :
 * commenter l'annotation pour lancer réellement (réseau + DB réels, ~165 backtests, quelques
 * minutes en pool multi-thread).
 */
@Disabled("Runner manuel réseau+DB réel — commenter cette annotation pour régénérer dataset.json en local")
@SpringBootTest(properties = "logging.level.fr.ses10doigts.tradeIO5=INFO")
@DisplayName("RainbowDcaBacktestService - export dataset visualiseur (réseau réel)")
@Log
class RainbowDcaVisualizerExportTest {

    @Autowired
    private RainbowDcaBacktestService rainbowDcaBacktestService;

    private static final BigDecimal BASE_AMOUNT = BigDecimal.valueOf(100);
    private static final String SYMBOL = "BTC";
    private static final Path OUTPUT_DIR = Path.of("target", "rainbow-dca-visualizer");
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());

    // --- Grilles OAT (mêmes valeurs candidates que RainbowDcaBacktestManualRunnerTest#boundsGrid()/
    //     reentryGrid()/smaGrid(), extraites en constantes indépendantes : ce lot ne fait varier
    //     qu'UN paramètre à la fois, jamais le cross-product complet) ---

    private static final List<BigDecimal> L_PERC_DOWN2 = List.of(bd(-13), bd(-11), bd(-9), bd(-7), bd(-5));
    private static final List<BigDecimal> L_PERC_DOWN1 = List.of(bd(-6), bd(-5), bd(-4), bd(-3), bd(-2));
    private static final List<BigDecimal> L_PERC_UP1 = List.of(bd(2), bd(3), bd(4), bd(6), bd(8));
    private static final List<BigDecimal> L_PERC_UP2 = List.of(bd(5), bd(7), bd(9), bd(11), bd(13));
    private static final List<BigDecimal> L_PERC_UP3 = List.of(bd(10), bd(12), bd(14), bd(16), bd(18));
    private static final List<ReentryMode> L_REENTRY_MODE = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
    private static final List<BigDecimal> L_TRAILING_STOP_PERCENT = List.of(bd(3), bd(5), bd(7));
    private static final List<Integer> L_COOLDOWN_DAYS = List.of(1, 3, 7, 12);
    private static final List<Integer> L_FIXED_DELAY_DAYS = List.of(3, 5, 10, 15);
    private static final List<BigDecimal> L_SELL_FRACTION = List.of(
            new BigDecimal("0.10"), new BigDecimal("0.25"), new BigDecimal("0.33"), new BigDecimal("0.5"), new BigDecimal("0.75"));
    private static final List<Integer> L_SMA_PERIOD = List.of(10, 14, 16, 18, 20, 22, 24, 26);

    /** Une fenêtre historique fixe associée à un preset (mêmes dates que RainbowDcaBacktestManualRunnerTest#WINDOWS). */
    private record Window(String label, LocalDate startDate, LocalDate endDate) {
    }

    /** Jeu de paramètres complet d'un preset UP/DOWN/RANGE (valeurs stabilisées, cf. tableau du prompt). */
    private record PresetDef(
            String name, Window window,
            BigDecimal percDown2, BigDecimal percDown1, BigDecimal percUp1, BigDecimal percUp2, BigDecimal percUp3,
            ReentryMode buyReentryMode, ReentryMode sellReentryMode, BigDecimal trailingStopPercent,
            int cooldownDays, int fixedDelayDays, BigDecimal sellFraction, int smaPeriod
    ) {
    }

    private static final List<PresetDef> PRESETS = List.of(
            new PresetDef("UP", new Window("BULL_2023_2024", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31)),
                    bd(-7), bd(-5), bd(2), bd(5), bd(16),
                    ReentryMode.FIXED_DELAY, ReentryMode.FIXED_DELAY, bd(3), 7, 15, new BigDecimal("0.10"), 20),
            new PresetDef("DOWN", new Window("BEAR_2021_2022", LocalDate.of(2021, 11, 10), LocalDate.of(2022, 12, 31)),
                    bd(-5), bd(-2), bd(3), bd(5), bd(12),
                    ReentryMode.FIXED_DELAY, ReentryMode.TRAILING_STOP, bd(3), 12, 15, new BigDecimal("0.75"), 20),
            new PresetDef("RANGE", new Window("SIDEWAYS_2018_2019", LocalDate.of(2022, 6, 20), LocalDate.of(2023, 3, 8)),
                    bd(-5), bd(-2), bd(3), bd(5), bd(16),
                    ReentryMode.FIXED_DELAY, ReentryMode.FIXED_DELAY, bd(3), 12, 10, new BigDecimal("0.10"), 20)
    );

    @Test
    @DisplayName("Exporte dataset.json (3 presets x baseline + sensibilité OAT sur 12 paramètres)")
    void exportVisualizerDataset() throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        for (PresetDef preset : PRESETS) {
            warmUpCache(preset);
        }

        Map<String, Object> presetsNode = new LinkedHashMap<>();
        for (PresetDef preset : PRESETS) {
            presetsNode.put(preset.name(), buildPresetNode(preset));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("symbol", SYMBOL);
        root.put("baseAmount", BASE_AMOUNT);
        root.put("presets", presetsNode);
        root.put("grids", buildGridsNode());

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        Path outputFile = OUTPUT_DIR.resolve("dataset.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
        log.info("dataset.json exporté : " + outputFile.toAbsolutePath() + " (" + Files.size(outputFile) + " octets)");
    }

    /** Un backtest séquentiel par preset (plus grande période de SMA de la grille) pour peupler le cache H1 avant la parallélisation. */
    private void warmUpCache(PresetDef preset) {
        int maxSmaPeriod = L_SMA_PERIOD.stream().mapToInt(Integer::intValue).max().orElseThrow();
        try {
            rainbowDcaBacktestService.backtest(baseRequestBuilder(preset).smaPeriod(maxSmaPeriod).build());
            log.info("Cache H1 pré-chauffé pour " + preset.name() + " (" + preset.window().label() + ")");
        } catch (Exception e) {
            log.warning("Pré-chauffe cache échouée pour " + preset.name() + " (les threads parallèles retenteront individuellement) : " + e.getMessage());
        }
    }

    private RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder baseRequestBuilder(PresetDef preset) {
        return RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                .startDate(preset.window().startDate()).endDate(preset.window().endDate())
                .percDown2(preset.percDown2()).percDown1(preset.percDown1())
                .percUp1(preset.percUp1()).percUp2(preset.percUp2()).percUp3(preset.percUp3())
                .buyReentryMode(preset.buyReentryMode()).sellReentryMode(preset.sellReentryMode())
                .trailingStopPercent(preset.trailingStopPercent())
                .cooldownDays(preset.cooldownDays()).fixedDelayDays(preset.fixedDelayDays())
                .sellFraction(preset.sellFraction()).smaPeriod(preset.smaPeriod());
    }

    /** Une variation OAT candidate : nom du paramètre + requête prête à exécuter + label de la valeur. */
    private record VariationJob(String paramName, Object candidateValue, RainbowDcaBacktestRequest request) {
    }

    private Map<String, Object> buildPresetNode(PresetDef preset) {
        List<VariationJob> jobs = buildVariationJobs(preset);

        // baseline + toutes les variations OAT, exécutées en parallèle (indépendantes, cf. RainbowDcaBacktestService#backtest sans état partagé)
        Map<String, RainbowDcaBacktestResult> resultsByKey = new ConcurrentHashMap<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        tasks.add(() -> {
            resultsByKey.put("baseline", rainbowDcaBacktestService.backtest(baseRequestBuilder(preset).build()));
            return null;
        });
        for (VariationJob job : jobs) {
            tasks.add(() -> {
                resultsByKey.put(job.paramName() + "|" + job.candidateValue(), rainbowDcaBacktestService.backtest(job.request()));
                return null;
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant l'export du preset " + preset.name(), e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Échec inattendu pendant l'export du preset " + preset.name(), e.getCause());
        } finally {
            executor.shutdown();
        }

        Map<String, Object> presetNode = new LinkedHashMap<>();
        presetNode.put("window", Map.of(
                "label", preset.window().label(),
                "startDate", preset.window().startDate(),
                "endDate", preset.window().endDate()));
        presetNode.put("parameters", parametersToMap(baseRequestBuilder(preset).build()));
        presetNode.put("baseline", buildEntryNode(resultsByKey.get("baseline")));

        Map<String, List<Object>> variationsNode = new LinkedHashMap<>();
        for (VariationJob job : jobs) {
            RainbowDcaBacktestResult result = resultsByKey.get(job.paramName() + "|" + job.candidateValue());
            Map<String, Object> entry = buildEntryNode(result);
            entry.put("value", job.candidateValue());
            variationsNode.computeIfAbsent(job.paramName(), k -> new ArrayList<>()).add(entry);
        }
        presetNode.put("variations", variationsNode);
        log.info("preset " + preset.name() + " : " + (1 + jobs.size()) + " backtests exportés (1 baseline + " + jobs.size() + " variations OAT)");
        return presetNode;
    }

    /** Construit, pour un preset donné, la liste des requêtes OAT (un seul paramètre différent du preset à la fois). */
    private List<VariationJob> buildVariationJobs(PresetDef preset) {
        List<VariationJob> jobs = new ArrayList<>();

        for (BigDecimal v : L_PERC_DOWN2) {
            if (isOrdered(v, preset.percDown1(), preset.percUp1(), preset.percUp2(), preset.percUp3())) {
                jobs.add(new VariationJob("percDown2", v, baseRequestBuilder(preset).percDown2(v).build()));
            }
        }
        for (BigDecimal v : L_PERC_DOWN1) {
            if (isOrdered(preset.percDown2(), v, preset.percUp1(), preset.percUp2(), preset.percUp3())) {
                jobs.add(new VariationJob("percDown1", v, baseRequestBuilder(preset).percDown1(v).build()));
            }
        }
        for (BigDecimal v : L_PERC_UP1) {
            if (isOrdered(preset.percDown2(), preset.percDown1(), v, preset.percUp2(), preset.percUp3())) {
                jobs.add(new VariationJob("percUp1", v, baseRequestBuilder(preset).percUp1(v).build()));
            }
        }
        for (BigDecimal v : L_PERC_UP2) {
            if (isOrdered(preset.percDown2(), preset.percDown1(), preset.percUp1(), v, preset.percUp3())) {
                jobs.add(new VariationJob("percUp2", v, baseRequestBuilder(preset).percUp2(v).build()));
            }
        }
        for (BigDecimal v : L_PERC_UP3) {
            if (isOrdered(preset.percDown2(), preset.percDown1(), preset.percUp1(), preset.percUp2(), v)) {
                jobs.add(new VariationJob("percUp3", v, baseRequestBuilder(preset).percUp3(v).build()));
            }
        }
        for (ReentryMode v : L_REENTRY_MODE) {
            jobs.add(new VariationJob("buyReentryMode", v.name(), baseRequestBuilder(preset).buyReentryMode(v).build()));
        }
        for (ReentryMode v : L_REENTRY_MODE) {
            jobs.add(new VariationJob("sellReentryMode", v.name(), baseRequestBuilder(preset).sellReentryMode(v).build()));
        }
        for (BigDecimal v : L_TRAILING_STOP_PERCENT) {
            jobs.add(new VariationJob("trailingStopPercent", v, baseRequestBuilder(preset).trailingStopPercent(v).build()));
        }
        for (Integer v : L_COOLDOWN_DAYS) {
            jobs.add(new VariationJob("cooldownDays", v, baseRequestBuilder(preset).cooldownDays(v).build()));
        }
        for (Integer v : L_FIXED_DELAY_DAYS) {
            jobs.add(new VariationJob("fixedDelayDays", v, baseRequestBuilder(preset).fixedDelayDays(v).build()));
        }
        for (BigDecimal v : L_SELL_FRACTION) {
            jobs.add(new VariationJob("sellFraction", v, baseRequestBuilder(preset).sellFraction(v).build()));
        }
        for (Integer v : L_SMA_PERIOD) {
            jobs.add(new VariationJob("smaPeriod", v, baseRequestBuilder(preset).smaPeriod(v).build()));
        }
        return jobs;
    }

    private static boolean isOrdered(BigDecimal down2, BigDecimal down1, BigDecimal up1, BigDecimal up2, BigDecimal up3) {
        return down2.compareTo(down1) < 0 && down1.compareTo(up1) < 0 && up1.compareTo(up2) < 0 && up2.compareTo(up3) < 0;
    }

    /** Résumé chiffré (mêmes champs que le runner de calibration §25/§26) + occurrences x3/vente pour un backtest exécuté. */
    private Map<String, Object> buildEntryNode(RainbowDcaBacktestResult r) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("parameters", parametersToMap(r.getParameters()));
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

    /** Uniquement les achats x3 déclenchés et les ventes déclenchées (jamais les achats de zone simples). */
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
        m.put("percDown2", r.getPercDown2());
        m.put("percDown1", r.getPercDown1());
        m.put("percUp1", r.getPercUp1());
        m.put("percUp2", r.getPercUp2());
        m.put("percUp3", r.getPercUp3());
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

    /** Grilles complètes (mêmes valeurs que les variations OAT), pour construire les sliders/dropdowns côté HTML. */
    private Map<String, Object> buildGridsNode() {
        Map<String, Object> grids = new LinkedHashMap<>();
        grids.put("percDown2", L_PERC_DOWN2);
        grids.put("percDown1", L_PERC_DOWN1);
        grids.put("percUp1", L_PERC_UP1);
        grids.put("percUp2", L_PERC_UP2);
        grids.put("percUp3", L_PERC_UP3);
        grids.put("buyReentryMode", L_REENTRY_MODE.stream().map(Enum::name).toList());
        grids.put("sellReentryMode", L_REENTRY_MODE.stream().map(Enum::name).toList());
        grids.put("trailingStopPercent", L_TRAILING_STOP_PERCENT);
        grids.put("cooldownDays", L_COOLDOWN_DAYS);
        grids.put("fixedDelayDays", L_FIXED_DELAY_DAYS);
        grids.put("sellFraction", L_SELL_FRACTION);
        grids.put("smaPeriod", L_SMA_PERIOD);
        return grids;
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    /**
     * Substitue, uniquement pour ce test, un {@link DcaCalculatorService} qui mémoïse
     * {@code calculate(..., valuationInstant)} par jeu d'arguments exact — même mécanisme que
     * {@link RainbowDcaBacktestManualRunnerTest.MemoizingDcaCalculatorServiceConfig}, dupliqué ici
     * pour ne pas coupler ce runner au précédent (cf. javadoc de la classe d'origine pour le détail).
     */
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
        ) {
        }
    }
}
