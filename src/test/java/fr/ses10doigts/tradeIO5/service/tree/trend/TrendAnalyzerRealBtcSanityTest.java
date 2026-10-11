package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de "sanity check" de {@link TrendAnalyzer} sur données réelles — même patron que
 * {@link SwingStructureCalculatorRealBtcSanityTest} (Étape 1) : lit directement
 * {@code tools/calibration/btc_klines_d1.csv} (aucune copie dans {@code src/test/resources}, aucune
 * librairie CSV), fait tourner le calculateur sur tout l'historique disponible, puis vérifie des
 * assertions structurelles uniquement (Étape 8 de la roadmap Trend unifié, prompt §5 : "les trois
 * états apparaissent, parts de UP/DOWN chacune entre 30 et 60%, nombre de changements par an dans
 * une fourchette large de 20 à 70") — aucune date ni valeur en dur.
 */
class TrendAnalyzerRealBtcSanityTest {

    private static final Path CSV_PATH = Path.of("tools", "calibration", "btc_klines_d1.csv");

    @Test
    @DisplayName("Données BTC réelles (klines D1) : les 3 états apparaissent, UP/DOWN chacun entre 30% et 60%, 20 à 70 changements/an")
    void realBtcData_producesPlausibleRegimeDistribution() {
        List<MarketData> candles = loadAll();
        assertTrue(candles.size() >= TrendAnalyzer.MIN_CANDLES, "historique trop court pour ce sanity check");

        TrendAnalyzer analyzer = new TrendAnalyzer();

        List<TrendState> timeline = assertDoesNotThrow(() -> analyzer.analyzeTimeline(candles,
                RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG,
                RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED));

        long upCount = timeline.stream().filter(s -> s.regime() == TrendRegime.UP).count();
        long downCount = timeline.stream().filter(s -> s.regime() == TrendRegime.DOWN).count();
        long rangeCount = timeline.stream().filter(s -> s.regime() == TrendRegime.RANGE).count();

        assertTrue(upCount > 0, "aucun regime UP observe sur tout l'historique");
        assertTrue(downCount > 0, "aucun regime DOWN observe sur tout l'historique");
        assertTrue(rangeCount > 0, "aucun regime RANGE observe sur tout l'historique");

        double upShare = upCount / (double) timeline.size();
        double downShare = downCount / (double) timeline.size();
        assertTrue(upShare >= 0.30 && upShare <= 0.60, "part de UP hors fourchette [30%,60%] : " + upShare);
        assertTrue(downShare >= 0.30 && downShare <= 0.60, "part de DOWN hors fourchette [30%,60%] : " + downShare);

        int changes = 0;
        for (int i = 1; i < timeline.size(); i++) {
            if (timeline.get(i).regime() != timeline.get(i - 1).regime()) {
                changes++;
            }
        }
        double years = Duration.between(candles.get(candles.size() - timeline.size()).getTimestamp(), candles.getLast().getTimestamp()).toDays() / 365.25;
        double changesPerYear = changes / years;
        assertTrue(changesPerYear >= 20 && changesPerYear <= 70,
                "changements/an hors fourchette [20,70] : " + changesPerYear + " (changes=" + changes + ", years=" + years + ")");
    }

    private static List<MarketData> loadAll() {
        List<String> lines;
        try {
            lines = Files.readAllLines(CSV_PATH);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de lire " + CSV_PATH, e);
        }

        List<MarketData> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split(",");
            Instant timestamp = OffsetDateTime.parse(cols[0]).toInstant();
            result.add(MarketData.builder()
                    .timeFrame(TimeFrame.D1)
                    .timestamp(timestamp)
                    .pair("BTCUSDT")
                    .open(new BigDecimal(cols[1]))
                    .high(new BigDecimal(cols[2]))
                    .low(new BigDecimal(cols[3]))
                    .close(new BigDecimal(cols[4]))
                    .volume(new BigDecimal(cols[5]))
                    .build());
        }
        return result;
    }
}
