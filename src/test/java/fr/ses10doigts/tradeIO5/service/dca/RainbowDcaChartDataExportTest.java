package fr.ses10doigts.tradeIO5.service.dca;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.Bucket;
import lombok.extern.java.Log;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export des bougies D1 BTCUSDT (meme resampling H1->D1 via {@link Bucket} que
 * {@link RainbowDcaBacktestService#backtest}) pour embarquer un historique de prix statique dans
 * le visualiseur (2026-09-27) : un artifact Claude ne peut PAS faire de fetch() reseau vers
 * api.binance.com au runtime (CSP, seuls les CDN de script sont autorises) - le graphique doit
 * donc recevoir ses prix deja calcules plutot que de les demander lui-meme au navigateur.
 */
@Disabled("Export deja effectue - reactiver au besoin pour rafraichir")
@SpringBootTest(properties = "logging.level.fr.ses10doigts.tradeIO5=INFO")
@DisplayName("Export bougies D1 BTC pour visualiseur statique")
@Log
class RainbowDcaChartDataExportTest {

    @Autowired
    @Qualifier("cachingBinanceMarketDataApiClient")
    private MarketDataApiClient binanceClient;

    @Autowired
    private DomainClock clock;

    private static final Path OUTPUT_DIR = Path.of("target", "rainbow-dca-visualizer");

    @Test
    @DisplayName("Exporte candles-btc-d1.json (juil 2024 -> aujourd'hui)")
    void exportDailyCandles() throws IOException {
        Instant now = clock.now();
        LocalDate startDate = LocalDate.of(2024, 7, 1);
        Instant fetchFrom = startDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        List<MarketData> hourly = new ArrayList<>();
        Instant chunkStart = fetchFrom;
        while (chunkStart.isBefore(now)) {
            Instant chunkEnd = chunkStart.plusSeconds(900L * 3600L);
            if (chunkEnd.isAfter(now)) chunkEnd = now;
            hourly.addAll(binanceClient.getCandles("BTCUSDT", TimeFrame.H1, chunkStart, chunkEnd, 1000));
            chunkStart = chunkEnd;
        }
        hourly.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

        Bucket bucket = new Bucket(TimeFrame.H1, hourly.size() + 100);
        for (MarketData c : hourly) { bucket.append(c); }
        BucketView d1View = bucket.view(TimeFrame.D1, now);
        List<MarketData> d1 = new ArrayList<>(d1View.data());
        if (d1View.completeness() == CompletenessLevel.PARTIAL_LAST && !d1.isEmpty()) {
            d1.removeLast();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (MarketData c : d1) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", LocalDate.ofInstant(c.getTimestamp(), ZoneOffset.UTC).toString());
            row.put("open", c.getOpen());
            row.put("high", c.getHigh());
            row.put("low", c.getLow());
            row.put("close", c.getClose());
            out.add(row);
        }

        Files.createDirectories(OUTPUT_DIR);
        Path outputFile = OUTPUT_DIR.resolve("candles-btc-d1.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), out);
        log.info("candles-btc-d1.json exporte : " + outputFile.toAbsolutePath() + " (" + out.size() + " bougies)");
    }
}
