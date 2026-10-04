package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de "sanity check" de {@link SwingStructureCalculator} sur données réelles : lit directement
 * {@code tools/calibration/btc_klines_d1.csv} (aucune copie dans {@code src/test/resources}, aucune
 * librairie CSV - parsing manuel), puis fait tourner le calculateur.
 * <p>
 * Simplifié le 2026-09-19 : plus de série ATR à construire ({@link SwingStructureCalculator} n'a
 * plus de paramètre depuis sa réécriture sans ATR/seuil, cf. sa javadoc).
 * <p>
 * Assertions volontairement "structurelles" (pas de date/prix de pivot en dur) : le calcul ne doit
 * pas lever d'exception, le régime ne doit jamais être {@code null}, et la fenêtre choisie doit
 * produire au moins 2 pivots hauts et 2 pivots bas confirmés (matérialisé par
 * {@code previousSwingHigh}/{@code previousSwingLow} non nuls), avec un ordre chronologique et une
 * classification cohérente avec les prix.
 */
class SwingStructureCalculatorRealBtcSanityTest {

    private static final Path CSV_PATH = Path.of("tools", "calibration", "btc_klines_d1.csv");
    private static final Instant WINDOW_START = Instant.parse("2025-04-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-06-30T00:00:00Z");

    @Test
    @DisplayName("Données BTC réelles (klines D1) : pas d'exception, régime jamais null, >= 2 pivots hauts et bas confirmés")
    void realBtcData_producesCoherentStructure() {
        List<MarketData> candles = loadWindow();
        assertTrue(candles.size() > 10, "fenêtre trop courte pour ce sanity check");

        SwingStructureCalculator calculator = new SwingStructureCalculator();

        SwingStructureState state = assertDoesNotThrow(() -> calculator.compute(candles));

        assertNotNull(state.regime(), "le regime ne doit jamais etre null");

        // Au moins 2 pivots hauts et 2 pivots bas confirmes sur la fenetre : previousSwingXxx non nul
        // implique qu'un pivot anterieur existait deja avant le dernier confirme.
        assertNotNull(state.lastSwingHigh(), "aucun pivot haut confirme sur la fenetre");
        assertNotNull(state.lastSwingLow(), "aucun pivot bas confirme sur la fenetre");
        assertNotNull(state.previousSwingHigh(), "moins de 2 pivots hauts confirmes sur la fenetre");
        assertNotNull(state.previousSwingLow(), "moins de 2 pivots bas confirmes sur la fenetre");

        // Ordre chronologique : le pivot "previous" precede strictement le pivot "last" du meme type.
        assertTrue(state.previousSwingHigh().timestamp().isBefore(state.lastSwingHigh().timestamp()),
                "previousSwingHigh doit preceder lastSwingHigh");
        assertTrue(state.previousSwingLow().timestamp().isBefore(state.lastSwingLow().timestamp()),
                "previousSwingLow doit preceder lastSwingLow");

        // Coherence prix <-> classification (HH/LH pour les hauts, HL/LL pour les bas).
        assertNotNull(state.lastSwingHigh().classification());
        assertNotNull(state.lastSwingLow().classification());
        boolean lastHighIsHigher =
                state.lastSwingHigh().price().compareTo(state.previousSwingHigh().price()) > 0;
        assertTrue(lastHighIsHigher
                        ? state.lastSwingHigh().classification() == PivotClassification.HH
                        : state.lastSwingHigh().classification() == PivotClassification.LH,
                "classification du dernier pivot haut incoherente avec le prix");
        boolean lastLowIsHigher =
                state.lastSwingLow().price().compareTo(state.previousSwingLow().price()) > 0;
        assertTrue(lastLowIsHigher
                        ? state.lastSwingLow().classification() == PivotClassification.HL
                        : state.lastSwingLow().classification() == PivotClassification.LL,
                "classification du dernier pivot bas incoherente avec le prix");

        // nearestSrLevels : non vide, au plus 4 niveaux.
        assertFalse(state.nearestSrLevels().isEmpty());
        assertTrue(state.nearestSrLevels().size() <= 4);
    }

    private static List<MarketData> loadWindow() {
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
            if (timestamp.isBefore(WINDOW_START) || timestamp.isAfter(WINDOW_END)) {
                continue;
            }
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
