package fr.ses10doigts.tradeIO5.service.dca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import lombok.extern.java.Log;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Disabled("Analyse ponctuelle deja executee - reactiver au besoin pour recalculer")
@SpringBootTest(properties = "logging.level.fr.ses10doigts.tradeIO5=INFO")
@DisplayName("Analyse volatilite + backtest cible sur la question 'pourquoi si peu de ventes UP recentes'")
@Log
class RainbowDcaVolatilityAnalysisTest {

    @Autowired
    private RainbowDcaBacktestService rainbowDcaBacktestService;

    @Autowired
    @Qualifier("cachingBinanceMarketDataApiClient")
    private MarketDataApiClient binanceClient;

    private static final BigDecimal BASE_AMOUNT = BigDecimal.valueOf(100);
    private static final String SYMBOL = "BTC";
    private static final Path OUTPUT_DIR = Path.of("target", "rainbow-dca-visualizer");

    private record Window(String label, LocalDate startDate, LocalDate endDate) { }

    private static final Window CALIB_UP = new Window("BULL_2023_2024", LocalDate.of(2023, 1, 1), LocalDate.of(2024, 12, 31));
    private static final Window RECENT_BULL = new Window("NOV2024_OCT2025", LocalDate.of(2024, 11, 1), LocalDate.of(2025, 10, 8));

    @Test
    @DisplayName("Backtest UP courant sur Nov2024->Oct2025 + comparaison volatilite avec BULL_2023_2024")
    void analyze() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());

        RainbowDcaBacktestResult recentResult = rainbowDcaBacktestService.backtest(
                RainbowDcaBacktestRequest.builder()
                        .symbol(SYMBOL).baseAmount(BASE_AMOUNT)
                        .startDate(RECENT_BULL.startDate()).endDate(RECENT_BULL.endDate())
                        .percDown2(BigDecimal.valueOf(-7)).percDown1(BigDecimal.valueOf(-5))
                        .percUp1(BigDecimal.valueOf(2)).percUp2(BigDecimal.valueOf(5)).percUp3(BigDecimal.valueOf(16))
                        .buyReentryMode(ReentryMode.FIXED_DELAY).sellReentryMode(ReentryMode.FIXED_DELAY)
                        .trailingStopPercent(BigDecimal.valueOf(3))
                        .cooldownDays(7).fixedDelayDays(15)
                        .sellFraction(new BigDecimal("0.10")).smaPeriod(20)
                        .build());
        root.put("recentBacktest", summarize(recentResult, RECENT_BULL));

        Map<String, Object> vol = new LinkedHashMap<>();
        log.info("Volatilite : calcul RECENT_BULL...");
        vol.put(RECENT_BULL.label(), computeVolatilityStats(RECENT_BULL));
        log.info("Volatilite : calcul CALIB_UP...");
        vol.put(CALIB_UP.label(), computeVolatilityStats(CALIB_UP));
        root.put("volatility", vol);

        Files.createDirectories(OUTPUT_DIR);
        Path outputFile = OUTPUT_DIR.resolve("volatility-analysis.json");
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);
        log.info("volatility-analysis.json exporte : " + outputFile.toAbsolutePath());
    }

    private Map<String, Object> summarize(RainbowDcaBacktestResult r, Window w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("window", Map.of("label", w.label(), "start", w.startDate(), "end", w.endDate()));
        m.put("buyTriggeredCount", r.getBuyTriggeredCount());
        m.put("sellTriggeredCount", r.getSellTriggeredCount());
        m.put("totalInvested", r.getTotalInvested());
        m.put("totalSaleProceeds", r.getTotalSaleProceeds());
        m.put("pnlPercent", r.getPnlPercent());
        List<Map<String, Object>> occ = new ArrayList<>();
        for (RainbowDcaOccurrence o : r.getOccurrences()) {
            if (o.getBuyAction() == RainbowDcaOccurrence.BuyAction.TRIGGERED) {
                occ.add(Map.of("date", o.getDate(), "type", "BUY", "price", o.getClose()));
            }
            if (o.getSellAction() == RainbowDcaOccurrence.SellAction.TRIGGERED) {
                occ.add(Map.of("date", o.getDate(), "type", "SELL", "price", o.getClose()));
            }
        }
        m.put("occurrences", occ);
        return m;
    }

    private Map<String, Object> computeVolatilityStats(Window w) {
        Instant since = w.startDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = w.endDate().atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(86400);
        List<MarketData> hourly = new ArrayList<>();
        Instant chunkStart = since;
        while (chunkStart.isBefore(until)) {
            Instant chunkEnd = chunkStart.plusSeconds(900L * 3600L);
            if (chunkEnd.isAfter(until)) chunkEnd = until;
            hourly.addAll(binanceClient.getCandles("BTCUSDT", TimeFrame.H1, chunkStart, chunkEnd, 1000));
            chunkStart = chunkEnd;
        }
        hourly.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
        // Sample one candle per day (00:00 UTC) to approximate daily closes from H1 data.
        List<MarketData> candles = new ArrayList<>();
        long lastDay = -1;
        for (MarketData c : hourly) {
            long day = c.getTimestamp().getEpochSecond() / 86400;
            if (day != lastDay) {
                candles.add(c);
                lastDay = day;
            }
        }

        List<Double> dailyReturnsPct = new ArrayList<>();
        double maxClose = Double.MIN_VALUE;
        double minClose = Double.MAX_VALUE;
        double maxDrawdownPct = 0;
        double maxRallyPct = 0;
        double runningMax = -1, runningMin = Double.MAX_VALUE;
        BigDecimal prevClose = null;
        for (MarketData c : candles) {
            double close = c.getClose().doubleValue();
            maxClose = Math.max(maxClose, close);
            minClose = Math.min(minClose, close);
            if (prevClose != null) {
                double ret = (close - prevClose.doubleValue()) / prevClose.doubleValue() * 100.0;
                dailyReturnsPct.add(ret);
            }
            prevClose = c.getClose();
            // drawdown from running local max
            runningMax = Math.max(runningMax, close);
            double ddFromMax = (close - runningMax) / runningMax * 100.0;
            maxDrawdownPct = Math.min(maxDrawdownPct, ddFromMax);
            // rally from running local min
            runningMin = Math.min(runningMin, close);
            double rallyFromMin = (close - runningMin) / runningMin * 100.0;
            maxRallyPct = Math.max(maxRallyPct, rallyFromMin);
        }
        double mean = dailyReturnsPct.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = dailyReturnsPct.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        double stdDevDailyPct = Math.sqrt(variance);
        double annualizedVolPct = stdDevDailyPct * Math.sqrt(365);
        double meanAbsDailyMovePct = dailyReturnsPct.stream().mapToDouble(Math::abs).average().orElse(0);
        long daysAbove3pct = dailyReturnsPct.stream().filter(v -> Math.abs(v) > 3.0).count();
        long daysAbove5pct = dailyReturnsPct.stream().filter(v -> Math.abs(v) > 5.0).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("candleCount", candles.size());
        stats.put("firstClose", candles.isEmpty() ? null : candles.get(0).getClose());
        stats.put("lastClose", candles.isEmpty() ? null : candles.get(candles.size() - 1).getClose());
        stats.put("minClose", minClose);
        stats.put("maxClose", maxClose);
        stats.put("stdDevDailyReturnPct", round(stdDevDailyPct));
        stats.put("annualizedVolatilityPct", round(annualizedVolPct));
        stats.put("meanAbsDailyMovePct", round(meanAbsDailyMovePct));
        stats.put("daysWithAbsMoveAbove3pct", daysAbove3pct);
        stats.put("daysWithAbsMoveAbove5pct", daysAbove5pct);
        stats.put("maxDrawdownPct", round(maxDrawdownPct));
        stats.put("maxRallyFromLocalMinPct", round(maxRallyPct));
        return stats;
    }

    private static double round(double v) {
        return new BigDecimal(v, new MathContext(6)).doubleValue();
    }
}
