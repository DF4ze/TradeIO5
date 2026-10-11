package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste {@link SwingStructureIndicator} en câblage réel via {@link IndicatorEngine} (patron
 * {@code RainbowSmaIndicatorTest}).
 * <p>
 * Réécrit le 2026-09-19 : plus de paramètre {@code atrPeriod}/{@code atrMultiplier} ni de
 * dépendance ATR depuis la réécriture de {@code SwingStructureCalculator} (cf. sa javadoc)  -
 * l'indicateur se contente désormais des bougies du contexte.
 * <p>
 * La série nominale (zigzag haussier propre : montées régulières + 1 bougie de retournement net par
 * cycle) a été vérifiée par simulation indépendante de l'algorithme avant transcription : les
 * valeurs de régime/pivots ne sont pas devinées.
 */
@SpringBootTest
@DisplayName("Indicator - SWING_STRUCTURE")
class SwingStructureIndicatorTest {

    @Autowired
    private IndicatorEngine indicatorEngine;

    private static DomainClock clock;

    @BeforeAll
    static void init() {
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    private static List<MarketData> candles(double[][] ohlc) {
        Instant baseTimestamp = Instant.parse("2024-01-01T00:00:00Z");
        List<MarketData> candles = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) {
            candles.add(MarketData.builder()
                    .low(BigDecimal.valueOf(ohlc[i][0]))
                    .high(BigDecimal.valueOf(ohlc[i][1]))
                    .close(BigDecimal.valueOf(ohlc[i][2]))
                    .timestamp(baseTimestamp.plus(i, ChronoUnit.DAYS))
                    .build());
        }
        return candles;
    }

    private static IndicatorContext context(List<MarketData> candles) {
        MarketDataset series = MarketDataset.builder()
                .marketDatas(candles)
                .timeFrame(TimeFrame.D1)
                .build();
        return new IndicatorContext("BTCUSDT", series.getTimeFrame(), series, Map.of(), clock);
    }

    private static IndicatorParameters params() {
        return IndicatorParameters.builder()
                .indicatorType(IndicatorType.SWING_STRUCTURE)
                .numerics(Map.of())
                .build();
    }

    // 10 bougies calmes (warmup, pas indispensable mais garde la série réaliste) + 8 cycles
    // zigzag haussiers propres (4 bougies de montée régulière + 1 bougie de retournement net) :
    // régime final BULL_CONFIRMED, lastSwingHigh=HH@1340, lastSwingLow=HL@1310, vérifié par
    // simulation indépendante de l'algorithme.
    private static final double[][] CLEAN_BULL_ZIGZAG = {
            {999, 1001, 1000}, {999, 1001, 1000}, {999, 1001, 1000}, {999, 1001, 1000},
            {999, 1001, 1000}, {999, 1001, 1000}, {999, 1001, 1000}, {999, 1001, 1000},
            {999, 1001, 1000}, {999, 1001, 1000}, {1000, 1015, 1015}, {1015, 1030, 1030},
            {1030, 1045, 1045}, {1045, 1060, 1060}, {1040, 1060, 1040}, {1040, 1055, 1055},
            {1055, 1070, 1070}, {1070, 1085, 1085}, {1085, 1100, 1100}, {1080, 1100, 1080},
            {1080, 1095, 1095}, {1095, 1110, 1110}, {1110, 1125, 1125}, {1125, 1140, 1140},
            {1120, 1140, 1120}, {1120, 1135, 1135}, {1135, 1150, 1150}, {1150, 1165, 1165},
            {1165, 1180, 1180}, {1160, 1180, 1160}, {1160, 1175, 1175}, {1175, 1190, 1190},
            {1190, 1205, 1205}, {1205, 1220, 1220}, {1200, 1220, 1200}, {1200, 1215, 1215},
            {1215, 1230, 1230}, {1230, 1245, 1245}, {1245, 1260, 1260}, {1240, 1260, 1240},
            {1240, 1255, 1255}, {1255, 1270, 1270}, {1270, 1285, 1285}, {1285, 1300, 1300},
            {1280, 1300, 1280}, {1280, 1295, 1295}, {1295, 1310, 1310}, {1310, 1325, 1325},
            {1325, 1340, 1340}, {1320, 1340, 1320},
    };

    @Test
    @DisplayName("Liste de bougies vide -> résultat invalide")
    void emptyCandles_returnsInvalid() {
        IndicatorContext context = context(List.of());
        IndicatorSnapshot snap = indicatorEngine.execute(context, params());
        assertFalse(snap.getResult().isValid());
    }

    @Test
    @DisplayName("Cas nominal : régime BULL_CONFIRMED -> value=1.0, clés lastSwingHigh/lastSwingLow/sr présentes")
    void nominalCase_bullConfirmed_encodesRegimeAndPivots() {
        IndicatorContext context = context(candles(CLEAN_BULL_ZIGZAG));

        IndicatorSnapshot snap = indicatorEngine.execute(context, params());

        assertTrue(snap.getResult().isValid());
        assertEquals(1.0, snap.getResult().getValue());

        Map<String, Double> values = snap.getResult().getValues();
        assertNotNull(values);

        assertEquals(1340.0, values.get(SwingStructureIndicator.V_LAST_SWING_HIGH));
        assertNotNull(values.get(SwingStructureIndicator.V_LAST_SWING_HIGH_EPOCH_DAY));
        assertEquals(1310.0, values.get(SwingStructureIndicator.V_LAST_SWING_LOW));
        assertNotNull(values.get(SwingStructureIndicator.V_LAST_SWING_LOW_EPOCH_DAY));

        assertEquals(1300.0, values.get(SwingStructureIndicator.V_PREVIOUS_SWING_HIGH));
        assertEquals(1295.0, values.get(SwingStructureIndicator.V_PREVIOUS_SWING_LOW));

        // sr1..sr4 triés par proximité au dernier close (1320) : 1310(10), 1300(20), 1340(20), 1295(25).
        assertEquals(1310.0, values.get("sr1"));
        assertEquals(1300.0, values.get("sr2"));
        assertEquals(1340.0, values.get("sr3"));
        assertEquals(1295.0, values.get("sr4"));
    }
}
