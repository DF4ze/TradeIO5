package fr.ses10doigts.tradeIO5.service.dca;

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
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import java.util.function.Function;

/**
 * Runner manuel (réseau + DB réels) pour explorer le paramétrage du DCA Rainbow sur BTC — cf.
 * docs/prompts/prompt-implementation-dca-rainbow-v0.md, section "Comment lancer le backtest" : pas
 * de tool MCP ni d'endpoint REST dans ce lot (coût d'itération d'un tool MCP = un appel LLM par
 * essai). Désactivé par défaut ({@link Disabled}), même convention que {@code YoutubeManualNetworkTest}.
 * <p>
 * Réécriture du 2026-09-13 (cf. docs/etudes/etude-dca-tool-mcp.md §12), suite au constat du run
 * initial (§11) : aucune combinaison ne battait le DCA fixe classique. Ce constat venait d'un biais
 * de protocole, pas d'un verdict sur la stratégie — une seule fenêtre testée (BTC, 3 dernières
 * années, bull run quasi continu), sur laquelle une stratégie qui désinvestit en zone haute perd
 * mécaniquement contre un DCA qui reste engagé à 100 %. Corrections apportées ici :
 * <ol>
 *     <li>Trois fenêtres historiques FIXES (bull / bear / sideways, dates explicites plutôt que
 *     "3 dernières années glissantes") : la vraie proposition de valeur de Rainbow (vendre en haut,
 *     racheter plus bas) ne peut se voir que sur un cycle complet, et des dates fixes rendent le
 *     run reproductible d'une exécution à l'autre.</li>
 *     <li>Deux bancs d'essai séparés, BTC uniquement (demande explicite) :
 *     {@link #runBoundsCalibration_realBtcHistory()} cherche les bornes % valides (percDown2 →
 *     percUp3), {@link #runReentryMethodsComparison_realBtcHistory()} compare les méthodes de
 *     sortie des états ARMÉ ({@link ReentryMode} : trailing stop / franchissement pur / délai fixe)
 *     à bornes V0 fixées.</li>
 *     <li>Classement par ROBUSTESSE (delta de PnL% vs DCA fixe, minimum sur les 3 fenêtres) plutôt
 *     que par le seul meilleur PnL% d'une fenêtre — évite de retenir un jeu de paramètres qui n'a
 *     brillé qu'une fois par hasard sur une seule période (même défaut que la calibration
 *     rejection-zone avant son walk-forward, cf. mémoire projet).</li>
 *     <li>Max drawdown de l'exposition marché, calculé ici (pas ajouté à {@link RainbowDcaBacktestResult},
 *     qui n'en a pas besoin ailleurs) : une stratégie qui réduit l'exposition en haut de cycle peut
 *     perdre du PnL brut tout en réduisant le risque — invisible si on ne regarde que le PnL final
 *     sur un bull run.</li>
 *     <li>Export CSV (un fichier détail + un fichier agrégé par banc, sous
 *     {@code target/rainbow-dca-bench/}) en plus du résumé console — l'ancienne mise en page
 *     (largeur de colonne différente entre l'en-tête et les lignes, un seul run BTC) rendait
 *     l'analyse illisible ; {@link #printTable} calcule sa largeur de colonne sur le contenu réel.</li>
 *     <li>Exécution parallélisée (pool de threads = nb de coeurs) : chaque (jeu de paramètres,
 *     fenêtre) est un backtest indépendant (aucun état partagé mutable dans
 *     {@link RainbowDcaBacktestService#backtest}, uniquement des variables locales). Pour éviter
 *     que plusieurs threads ne déclenchent en même temps le même fetch réseau + insertion DB pour
 *     une fenêtre pas encore en cache, {@link #warmUpCache} lance UN backtest séquentiel par
 *     fenêtre avant de paralléliser — après quoi tous les threads lisent uniquement le cache DB
 *     ({@code CachingMarketDataApiClient}, déjà peuplé) pour cette plage H1.</li>
 * </ol>
 * Volontairement pas fait ici (cf. docs/CODING_RULES.md — "ne pas coder ce dont on n'a pas
 * besoin") : rendement pondéré dans le temps (XIRR) pour comparer des calendriers de cash-flows
 * différents entre Rainbow et le DCA fixe, multi-actif, split in-sample/out-of-sample formel.
 * <p>
 * {@link MemoizingDcaCalculatorServiceConfig} : mémoïse {@code DcaCalculatorService.calculate(...)}
 * par jeu d'arguments exact, pour ne payer le calcul (et l'appel réseau "prix courant", jamais
 * cachable en base tant que la bougie H1 n'est pas close) qu'une seule fois par fenêtre/montant sur
 * tout le run, au lieu d'une fois par scénario. Le cache interne est un {@code ConcurrentHashMap} :
 * thread-safe pour l'exécution parallélisée ci-dessus. DcaCalculatorService.java n'est pas modifié.
 */
@Disabled("Runner manuel réseau+DB réel — commenter cette annotation pour lancer les backtests BTC réels en local")
@SpringBootTest(properties = "logging.level.fr.ses10doigts.tradeIO5=INFO")
@DisplayName("RainbowDcaBacktestService - runners manuels BTC (réseau réel)")
@Log
class RainbowDcaBacktestManualRunnerTest {

    @Autowired
    private RainbowDcaBacktestService rainbowDcaBacktestService;

    private static final BigDecimal BASE_AMOUNT = BigDecimal.valueOf(100);
    private static final String SYMBOL = "BTC";
    private static final Path OUTPUT_DIR = Path.of("target", "rainbow-dca-bench");
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());

    /**
     * Fenêtres historiques fixes (pas de "3 dernières années glissantes") pour que le run soit
     * reproductible et couvre les 3 régimes de marché où la mécanique Rainbow peut réellement
     * s'exprimer : un bull soutenu, un bear complet (top -> bottom), une phase sideways.
     */
    private record Window(String label, LocalDate startDate, LocalDate endDate) {
    }

    private static final List<Window> WINDOWS = List.of(
            new Window("BULL_2023_2024", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31)),
            new Window("BEAR_2021_2022", LocalDate.of(2021, 11, 10), LocalDate.of(2022, 12, 31)),
            new Window("SIDEWAYS_2018_2019", LocalDate.of(2018, 7, 1), LocalDate.of(2019, 6, 30))
    );

    @Test
    @DisplayName("Bornes % valides pour BTC : grille sur percDown2/percDown1/percUp1/percUp2/percUp3, classée par robustesse sur 3 fenêtres")
    void runBoundsCalibration_realBtcHistory() throws IOException {
        List<BigDecimal> l_percDown2 = List.of(bd(-9), bd(-7), bd(-5));
        List<BigDecimal> l_percDown1 = List.of(bd(-4), bd(-3), bd(-2));
        List<BigDecimal> l_percUp1 = List.of(bd(2), bd(3), bd(4));
        List<BigDecimal> l_percUp2 = List.of(bd(5), bd(7), bd(9));
        List<BigDecimal> l_percUp3 = List.of(bd(10), bd(12), bd(14));

        List<RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder> builders = new ArrayList<>();
        for (BigDecimal down2 : l_percDown2) {
            for (BigDecimal down1 : l_percDown1) {
                for (BigDecimal up1 : l_percUp1) {
                    for (BigDecimal up2 : l_percUp2) {
                        for (BigDecimal up3 : l_percUp3) {
                            if (isOrdered(down2, down1, up1, up2, up3)) {
                                builders.add(RainbowDcaBacktestRequest.builder()
                                        .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                                        .percDown2(down2).percDown1(down1).percUp1(up1).percUp2(up2).percUp3(up3));
                            }
                        }
                    }
                }
            }
        }

        runAcrossWindowsAndReport("bounds-calibration", builders, this::describeBounds);
    }

    @Test
    @DisplayName("Méthodes de sortie ARMÉ (trailing stop / franchissement pur / délai fixe) : bornes V0 fixées, classé par robustesse sur 3 fenêtres")
    void runReentryMethodsComparison_realBtcHistory() throws IOException {
        List<ReentryMode> l_buyMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<ReentryMode> l_sellMode = List.of(ReentryMode.TRAILING_STOP, ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY);
        List<BigDecimal> l_trailingStopPercent = List.of(bd(3), bd(5), bd(7));
        List<Integer> l_cooldown = List.of(3, 7);
        List<Integer> l_fixedDelayDays = List.of(5, 10, 15);
        List<BigDecimal> l_sellFraction = List.of(new BigDecimal("0.25"), new BigDecimal("0.5"));

        List<RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder> builders = new ArrayList<>();
        for (ReentryMode buyMode : l_buyMode) {
            for (ReentryMode sellMode : l_sellMode) {
                for (BigDecimal ts : l_trailingStopPercent) {
                    for (int cd : l_cooldown) {
                        for (int fd : l_fixedDelayDays) {
                            for (BigDecimal sf : l_sellFraction) {
                                builders.add(RainbowDcaBacktestRequest.builder()
                                        .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                                        .buyReentryMode(buyMode).sellReentryMode(sellMode)
                                        .trailingStopPercent(ts).cooldownDays(cd)
                                        .fixedDelayDays(fd).sellFraction(sf));
                            }
                        }
                    }
                }
            }
        }

        runAcrossWindowsAndReport("reentry-methods", builders, this::describeReentry);
    }

    // ---------------------------------------------------------------------------------------
    // Moteur commun : pré-chauffe le cache H1 par fenêtre (séquentiel), lance chaque (jeu de
    // paramètres x fenêtre) en parallèle, calcule le delta vs DCA fixe et le max drawdown, classe
    // par robustesse (pire delta sur les 3 fenêtres), exporte tout en CSV et affiche un top 15
    // lisible en console.
    // ---------------------------------------------------------------------------------------

    private record ScenarioRow(
            String description, String window, RainbowDcaBacktestResult result,
            BigDecimal fixedPnlPercent, BigDecimal deltaVsFixed, BigDecimal maxDrawdownPercent
    ) {
    }

    private record AggregatedRow(
            String description, Map<String, BigDecimal> deltaByWindow, BigDecimal minDelta, BigDecimal avgDelta
    ) {
    }

    private record TaskResult(ScenarioRow row, String description, String windowLabel, String errorMessage) {
    }

    private void runAcrossWindowsAndReport(
            String benchName,
            List<RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder> builders,
            Function<RainbowDcaBacktestRequest, String> describe
    ) throws IOException {
        int totalRuns = builders.size() * WINDOWS.size();
        log.info(String.format("%s : %d jeu(x) de paramètres x %d fenêtres = %d runs (pool de %d threads)",
                benchName, builders.size(), WINDOWS.size(), totalRuns, THREAD_POOL_SIZE));

        warmUpCache(builders.getFirst());

        List<Callable<TaskResult>> tasks = new ArrayList<>();
        for (RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder builder : builders) {
            for (Window window : WINDOWS) {
                RainbowDcaBacktestRequest request = builder.startDate(window.startDate()).endDate(window.endDate()).build();
                String description = describe.apply(request);
                tasks.add(() -> runOneScenario(request, description, window));
            }
        }

        List<ScenarioRow> allRows = new ArrayList<>();
        Map<String, Map<String, BigDecimal>> deltasByDescription = new LinkedHashMap<>();
        int errorCount = 0;

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            List<Future<TaskResult>> futures = executor.invokeAll(tasks);
            for (Future<TaskResult> future : futures) {
                TaskResult taskResult = future.get();
                if (taskResult.row() != null) {
                    allRows.add(taskResult.row());
                    if (taskResult.row().deltaVsFixed() != null) {
                        deltasByDescription.computeIfAbsent(taskResult.description(), d -> new LinkedHashMap<>())
                                .put(taskResult.windowLabel(), taskResult.row().deltaVsFixed());
                    }
                } else {
                    errorCount++;
                    log.warning("ERROR : " + taskResult.description() + " / " + taskResult.windowLabel() + " : " + taskResult.errorMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompu pendant l'exécution parallèle de " + benchName, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Échec inattendu pendant l'exécution parallèle de " + benchName, e.getCause());
        } finally {
            executor.shutdown();
        }

        List<AggregatedRow> aggregated = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> entry : deltasByDescription.entrySet()) {
            Map<String, BigDecimal> deltas = entry.getValue();
            if (deltas.size() < WINDOWS.size()) {
                continue; // exclut les jeux de paramètres qui ont échoué sur au moins une fenêtre
            }
            BigDecimal min = deltas.values().stream().min(BigDecimal::compareTo).orElse(null);
            BigDecimal avg = deltas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(deltas.size()), 4, RoundingMode.HALF_UP);
            aggregated.add(new AggregatedRow(entry.getKey(), deltas, min, avg));
        }
        aggregated.sort(Comparator.comparing(
                (AggregatedRow row) -> row.minDelta(),
                Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())));

        writeDetailCsv(benchName, allRows);
        writeAggregatedCsv(benchName, aggregated);
        printLeaderboard(benchName, aggregated, 15);

        log.info(String.format("%s : %d runs, %d erreur(s), %d jeu(x) de paramètres valides sur les %d fenêtres",
                benchName, allRows.size(), errorCount, aggregated.size(), WINDOWS.size()));
    }

    /**
     * Lance UN backtest séquentiel par fenêtre avant la parallélisation, pour peupler le cache DB
     * H1 ({@code CachingMarketDataApiClient}) une seule fois par fenêtre. Sans ça, les premiers
     * threads du pool qui tombent sur la même fenêtre pas encore en cache déclencheraient tous en
     * même temps le même fetch réseau + la même insertion DB. La plage H1 fetchée ne dépend que de
     * {@code startDate}/{@code endDate}/{@code smaPeriod} (identiques pour tous les jeux de
     * paramètres d'un même banc), donc n'importe quel builder du banc convient pour le pré-chauffage.
     */
    private void warmUpCache(RainbowDcaBacktestRequest.RainbowDcaBacktestRequestBuilder templateBuilder) {
        for (Window window : WINDOWS) {
            try {
                rainbowDcaBacktestService.backtest(templateBuilder.startDate(window.startDate()).endDate(window.endDate()).build());
                log.info("Cache H1 pré-chauffé pour " + window.label());
            } catch (Exception e) {
                log.warning("Pré-chauffe cache échouée pour " + window.label() + " (les threads parallèles retenteront individuellement) : " + e.getMessage());
            }
        }
    }

    private TaskResult runOneScenario(RainbowDcaBacktestRequest request, String description, Window window) {
        try {
            RainbowDcaBacktestResult result = rainbowDcaBacktestService.backtest(request);
            DcaResult fixed = result.getFixedDcaComparison();
            BigDecimal fixedPnlPercent = fixed.getPnlPercent();
            BigDecimal rainbowPnlPercent = result.getPnlPercent();
            BigDecimal delta = (rainbowPnlPercent != null && fixedPnlPercent != null)
                    ? rainbowPnlPercent.subtract(fixedPnlPercent)
                    : null;
            BigDecimal maxDrawdown = computeMaxDrawdownPercent(result.getOccurrences());
            ScenarioRow row = new ScenarioRow(description, window.label(), result, fixedPnlPercent, delta, maxDrawdown);
            return new TaskResult(row, description, window.label(), null);
        } catch (Exception e) {
            return new TaskResult(null, description, window.label(), e.getMessage());
        }
    }

    private String describeBounds(RainbowDcaBacktestRequest r) {
        return String.format("pd2=%s pd1=%s pu1=%s pu2=%s pu3=%s",
                r.getPercDown2(), r.getPercDown1(), r.getPercUp1(), r.getPercUp2(), r.getPercUp3());
    }

    private String describeReentry(RainbowDcaBacktestRequest r) {
        return String.format("buy=%s sell=%s ts=%s%% cd=%dj fd=%dj sf=%s",
                r.getBuyReentryMode(), r.getSellReentryMode(), r.getTrailingStopPercent(),
                r.getCooldownDays(), r.getFixedDelayDays(), r.getSellFraction());
    }

    private static boolean isOrdered(BigDecimal down2, BigDecimal down1, BigDecimal up1, BigDecimal up2, BigDecimal up3) {
        return down2.compareTo(down1) < 0 && down1.compareTo(up1) < 0 && up1.compareTo(up2) < 0 && up2.compareTo(up3) < 0;
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    /**
     * Max drawdown (%) de l'exposition marché (position * close), calculé ici plutôt que dans
     * {@link RainbowDcaBacktestResult} : ne vaut que pour l'analyse de ce runner, ne fait pas
     * partie du contrat du service (cf. docs/CODING_RULES.md — ne pas coder ce dont on n'a pas
     * besoin ailleurs). Ignore volontairement le cash issu des ventes (non réinvesti) : mesure le
     * risque de l'exposition réellement au marché, pas la richesse totale.
     */
    private static BigDecimal computeMaxDrawdownPercent(List<RainbowDcaOccurrence> occurrences) {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (RainbowDcaOccurrence occ : occurrences) {
            BigDecimal equity = occ.getPositionAfter().multiply(occ.getClose());
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            if (peak.signum() > 0) {
                BigDecimal drawdown = peak.subtract(equity).divide(peak, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }
        return maxDrawdown;
    }

    // ---------------------------------------------------------------------------------------
    // Sorties : CSV complet (une ligne par scénario x fenêtre) + CSV agrégé (une ligne par jeu de
    // paramètres) + résumé console à largeur de colonne calculée dynamiquement (l'ancien format
    // avait une largeur d'en-tête différente de celle des lignes -> colonnes désalignées).
    // ---------------------------------------------------------------------------------------

    private void writeDetailCsv(String benchName, List<ScenarioRow> rows) throws IOException {
        List<String> headers = List.of("description", "window", "totalInvested", "zoneBuyCount", "buyTriggeredCount",
                "sellTriggeredCount", "avgBuyPrice", "pnlPercent", "fixedPnlPercent", "deltaVsFixed", "maxDrawdownPercent");
        List<List<String>> csvRows = new ArrayList<>();
        for (ScenarioRow row : rows) {
            RainbowDcaBacktestResult r = row.result();
            csvRows.add(List.of(
                    row.description(), row.window(), str(r.getTotalInvested()), String.valueOf(r.getZoneBuyCount()),
                    String.valueOf(r.getBuyTriggeredCount()), String.valueOf(r.getSellTriggeredCount()),
                    str(r.getAvgBuyPrice()), str(r.getPnlPercent()), str(row.fixedPnlPercent()),
                    str(row.deltaVsFixed()), str(row.maxDrawdownPercent())
            ));
        }
        writeCsv(benchName + "-detail", headers, csvRows);
    }

    private void writeAggregatedCsv(String benchName, List<AggregatedRow> rows) throws IOException {
        List<String> headers = new ArrayList<>(List.of("description"));
        for (Window w : WINDOWS) {
            headers.add("delta_" + w.label());
        }
        headers.add("minDelta");
        headers.add("avgDelta");

        List<List<String>> csvRows = new ArrayList<>();
        for (AggregatedRow row : rows) {
            List<String> csvRow = new ArrayList<>();
            csvRow.add(row.description());
            for (Window w : WINDOWS) {
                csvRow.add(str(row.deltaByWindow().get(w.label())));
            }
            csvRow.add(str(row.minDelta()));
            csvRow.add(str(row.avgDelta()));
            csvRows.add(csvRow);
        }
        writeCsv(benchName + "-aggregated", headers, csvRows);
    }

    private void printLeaderboard(String benchName, List<AggregatedRow> aggregated, int topN) {
        List<String> headers = new ArrayList<>(List.of("description"));
        for (Window w : WINDOWS) {
            headers.add("delta_" + w.label());
        }
        headers.add("minDelta");
        headers.add("avgDelta");

        List<List<String>> rows = new ArrayList<>();
        for (AggregatedRow row : aggregated.subList(0, Math.min(topN, aggregated.size()))) {
            List<String> r = new ArrayList<>();
            r.add(row.description());
            for (Window w : WINDOWS) {
                r.add(str(row.deltaByWindow().get(w.label())));
            }
            r.add(str(row.minDelta()));
            r.add(str(row.avgDelta()));
            rows.add(r);
        }
        log.info("===== " + benchName + " : top " + rows.size() + " par robustesse (delta PnL% vs DCA fixe, pire fenêtre) =====");
        printTable(headers, rows);
    }

    private static String str(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Écrit un CSV séparé par ";" (Excel FR) sous {@code target/rainbow-dca-bench/<name>-<timestamp>.csv}. */
    private void writeCsv(String name, List<String> headers, List<List<String>> rows) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path path = OUTPUT_DIR.resolve(name + "-" + timestamp + ".csv");
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(";", headers)).append(System.lineSeparator());
        for (List<String> row : rows) {
            sb.append(String.join(";", row)).append(System.lineSeparator());
        }
        Files.writeString(path, sb.toString());
        log.info("CSV écrit : " + path.toAbsolutePath());
    }

    /** Tableau console à largeur de colonne calculée sur le contenu réel (en-tête + lignes). */
    private static void printTable(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            widths[c] = headers.get(c).length();
        }
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        log.info(formatRow(headers, widths));
        for (List<String> row : rows) {
            log.info(formatRow(row, widths));
        }
    }

    private static String formatRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cells.size(); c++) {
            sb.append(String.format("%-" + (widths[c] + 2) + "s", cells.get(c)));
        }
        return sb.toString();
    }

    /**
     * Substitue, uniquement pour ce test, un {@link DcaCalculatorService} qui mémoïse
     * {@code calculate(...)} par jeu d'arguments exact (record {@link CalculateKey}). Repose sur
     * les mêmes beans que le vrai service (mêmes qualifiers) — seule la méthode {@code calculate}
     * change de comportement, via override d'une sous-classe anonyme qui délègue à
     * {@code super.calculate(...)} au premier appel pour chaque jeu d'arguments puis sert le
     * résultat en cache ensuite. {@code @Primary} : remplace le bean {@code @Service} standard
     * pour tout le contexte Spring de CE test uniquement (aucun impact hors de ce test). Le cache
     * interne ({@code ConcurrentHashMap}) est thread-safe pour l'exécution parallélisée ci-dessus.
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
                        int purchaseHourUtc, BigDecimal amount, BigDecimal feePercent, MarketDataSource source
                ) {
                    CalculateKey key = new CalculateKey(
                            symbol, startDate, endDate, frequency, purchaseHourUtc, amount, feePercent, source);
                    return cache.computeIfAbsent(key, k -> super.calculate(
                            symbol, startDate, endDate, frequency, purchaseHourUtc, amount, feePercent, source));
                }
            };
        }

        private record CalculateKey(
                String symbol, LocalDate startDate, LocalDate endDate, TimeFrame frequency,
                int purchaseHourUtc, BigDecimal amount, BigDecimal feePercent, MarketDataSource source
        ) {
        }
    }
}
