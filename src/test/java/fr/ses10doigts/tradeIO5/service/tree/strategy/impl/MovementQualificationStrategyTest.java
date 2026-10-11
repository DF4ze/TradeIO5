package fr.ses10doigts.tradeIO5.service.tree.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.OpenInterestIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Strategy - MovementQualification")
class MovementQualificationStrategyTest {

    private static final TimeFrame TF = TimeFrame.H1;

    // --- computeSignal() : logique pure, sans réseau ni Spring -----------------------------

    @Test
    @DisplayName("Cascade de liquidations : OI en forte baisse pendant un mouvement de prix marqué -> score négatif")
    void computeSignal_cascadeDeLiquidations() {
        MovementQualificationStrategy.MovementScore result = MovementQualificationStrategy.computeSignal(
                80.0, 100.0,   // oiCurrent, oiPrevious -> oiDelta = -0.20
                0.0001,        // funding quasi nul
                -500.0,        // obv négatif
                -0.05,         // priceChangePct : mouvement marqué (>= 2%)
                -0.10, 0.10,   // oiDeltaCascadeThreshold, oiDeltaBuildupThreshold
                0.0005, 0.01, 0.6, 0.3, // seuils funding
                0.02           // priceMoveThreshold
        );

        assertTrue(result.score() < 0, "score attendu négatif, obtenu " + result.score());
        assertTrue(result.reason().contains("cascade"), "reason attendue mentionnant 'cascade' : " + result.reason());
    }

    @Test
    @DisplayName("Conviction spot : OI stable/en hausse, funding neutre, volume confirmé -> score positif")
    void computeSignal_convictionSpot() {
        MovementQualificationStrategy.MovementScore result = MovementQualificationStrategy.computeSignal(
                105.0, 100.0,  // oiDelta = +0.05 (sous le seuil buildup 0.10)
                0.0001,        // funding quasi nul -> fundingSignal = 0 (sous le seuil bas)
                1000.0,        // obv positif -> volumeConfirmation = +1
                0.001,         // priceChangePct négligeable (pas de mouvement marqué)
                -0.10, 0.10,
                0.0005, 0.01, 0.6, 0.3,
                0.02
        );

        assertTrue(result.score() > 0, "score attendu positif, obtenu " + result.score());
        assertTrue(result.reason().contains("conviction"), "reason attendue mentionnant 'conviction' : " + result.reason());
    }

    @Test
    @DisplayName("Sur-effet-de-levier en construction : funding et OI en forte hausse pendant une hausse de prix -> score négatif d'alerte, distinct de la cascade")
    void computeSignal_surEffetDeLevierEnConstruction() {
        MovementQualificationStrategy.MovementScore result = MovementQualificationStrategy.computeSignal(
                115.0, 100.0,  // oiDelta = +0.15 (>= seuil buildup 0.10)
                0.009,         // funding fortement positif -> fundingSignal proche de 1
                200.0,         // obv positif (non déterminant pour ce cas)
                0.03,          // priceChangePct positif : hausse de prix
                -0.10, 0.10,
                0.0005, 0.01, 0.6, 0.3,
                0.02
        );

        assertTrue(result.score() < 0, "score attendu négatif, obtenu " + result.score());
        assertTrue(result.reason().contains("sur-effet-de-levier"), "reason attendue mentionnant 'sur-effet-de-levier' : " + result.reason());
        assertFalse(result.reason().contains("cascade"), "reason ne doit pas être confondue avec le cas cascade : " + result.reason());
    }

    @Test
    @DisplayName("Aucun pattern net -> score neutre (0)")
    void computeSignal_noPattern() {
        MovementQualificationStrategy.MovementScore result = MovementQualificationStrategy.computeSignal(
                100.5, 100.0,  // oiDelta légèrement positif mais...
                0.003,         // funding modérément positif (au-dessus de la bande neutre 0.3, en dessous du seuil buildup)
                -50.0,         // obv négatif -> pas de volumeConfirmation positive
                0.001,
                -0.10, 0.10,
                0.0005, 0.01, 0.6, 0.3,
                0.02
        );

        assertEquals(0.0, result.score(), 0.0001);
    }

    // --- accepts() ---------------------------------------------------------------------------

    @Test
    @DisplayName("accepts() vrai seulement avec exactement 1 OPEN_INTEREST + 1 FUNDING_RATE + 1 OBV")
    void accepts_trueOnlyWithExactlyOneOfEachExpectedType() {
        MovementQualificationStrategy strategy = new MovementQualificationStrategy(
                new IndicatorRegistry(List.of()), mock(IndicatorEngine.class));

        assertTrue(strategy.accepts(paramsWith(IndicatorType.OPEN_INTEREST, IndicatorType.FUNDING_RATE, IndicatorType.OBV)));
        assertFalse(strategy.accepts(paramsWith(IndicatorType.OPEN_INTEREST, IndicatorType.FUNDING_RATE)));
        assertFalse(strategy.accepts(paramsWith(IndicatorType.EMA, IndicatorType.ADX, IndicatorType.RSI)));
    }

    private StrategyParameters paramsWith(IndicatorType... types) {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = new HashMap<>();
        for (IndicatorType type : types) {
            IndicatorParameters ip = IndicatorParameters.builder()
                    .indicatorType(type)
                    .numerics(Map.of())
                    .strings(Map.of())
                    .booleans(Map.of())
                    .build();
            indicatorParameters.put(new IndicatorKey(type, TF, ip), ip);
        }
        StrategyParameters sp = new StrategyParameters();
        sp.setIndicatorParameters(indicatorParameters);
        return sp;
    }

    // --- evaluate() : orchestration avec IndicatorEngine mocké (pas de réseau/Spring) --------

    @Test
    @DisplayName("evaluate() retourne notValid() quand un des 3 indicateurs est invalid")
    void evaluate_returnsNotValid_whenOneIndicatorInvalid() {
        IndicatorEngine engine = mock(IndicatorEngine.class);

        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.OPEN_INTEREST)))
                .thenReturn(snapshotFor(IndicatorType.OPEN_INTEREST, IndicatorResult.invalid()));
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.FUNDING_RATE)))
                .thenReturn(snapshotFor(IndicatorType.FUNDING_RATE, IndicatorResult.builder().valid(true).value(0.001).build()));
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.OBV)))
                .thenReturn(snapshotFor(IndicatorType.OBV, IndicatorResult.builder().valid(true).value(100.0).build()));

        MovementQualificationStrategy strategy = new MovementQualificationStrategy(new IndicatorRegistry(List.of()), engine);

        StrategyParameters parameters = fullParameters();
        MarketContext context = marketContext(flatDataset());

        StrategySignal signal = strategy.evaluate(context, parameters);

        assertFalse(signal.isValid());
    }

    @Test
    @DisplayName("evaluate() : bout en bout, cas conviction spot -> BULLISH, score positif")
    void evaluate_endToEnd_convictionSpot() {
        IndicatorEngine engine = mock(IndicatorEngine.class);

        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.OPEN_INTEREST)))
                .thenReturn(snapshotFor(IndicatorType.OPEN_INTEREST, IndicatorResult.builder()
                        .valid(true)
                        .values(Map.of(OpenInterestIndicator.V_CURRENT, 105.0, OpenInterestIndicator.V_PREVIOUS, 100.0))
                        .build()));
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.FUNDING_RATE)))
                .thenReturn(snapshotFor(IndicatorType.FUNDING_RATE, IndicatorResult.builder().valid(true).value(0.0001).build()));
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.OBV)))
                .thenReturn(snapshotFor(IndicatorType.OBV, IndicatorResult.builder().valid(true).value(1000.0).build()));

        MovementQualificationStrategy strategy = new MovementQualificationStrategy(new IndicatorRegistry(List.of()), engine);

        StrategyParameters parameters = fullParameters();
        MarketContext context = marketContext(flatDataset());

        StrategySignal signal = strategy.evaluate(context, parameters);

        assertTrue(signal.isValid());
        assertTrue(signal.getScore() > 0);
        assertEquals(SignalType.BULLISH, signal.getType());
    }

    private IndicatorSnapshot snapshotFor(IndicatorType type, IndicatorResult result) {
        return IndicatorSnapshot.builder()
                .indicatorType(type)
                .result(result)
                .build();
    }

    private StrategyParameters fullParameters() {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = new HashMap<>();
        for (IndicatorType type : List.of(IndicatorType.OPEN_INTEREST, IndicatorType.FUNDING_RATE, IndicatorType.OBV)) {
            IndicatorParameters ip = IndicatorParameters.builder()
                    .indicatorType(type)
                    .numerics(Map.of())
                    .strings(Map.of(MovementQualificationStrategy.P_TIME_FRAME_NAME, "H1"))
                    .booleans(Map.of())
                    .build();
            indicatorParameters.put(new IndicatorKey(type, TF, ip), ip);
        }
        StrategyParameters sp = new StrategyParameters();
        sp.setIndicatorParameters(indicatorParameters);
        return sp;
    }

    private MarketContext marketContext(MarketDataset dataset) {
        return new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                Instant::now,
                Map.of(TF, dataset),
                new HashMap<>()
        );
    }

    /** 11 bougies à prix constant (100) : priceChangePct = 0 -> ne déclenche jamais "mouvement marqué". */
    private MarketDataset flatDataset() {
        List<MarketData> candles = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            candles.add(MarketData.builder()
                    .timeFrame(TF)
                    .timestamp(Instant.now().minusSeconds((11 - i) * 3600L))
                    .pair("BTCUSDT")
                    .open(new BigDecimal("100"))
                    .high(new BigDecimal("100"))
                    .low(new BigDecimal("100"))
                    .close(new BigDecimal("100"))
                    .volume(new BigDecimal("10"))
                    .build());
        }
        return MarketDataset.builder()
                .pair("BTCUSDT")
                .timeFrame(TF)
                .marketDatas(candles)
                .size(candles.size())
                .lastUpdate(Instant.now())
                .isComplete(true)
                .build();
    }
}
