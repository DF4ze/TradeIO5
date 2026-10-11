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
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.LiquidationsIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.OrderBookIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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

@DisplayName("Strategy - OrderFlow (ORDER_BOOK + LIQUIDATIONS)")
class OrderFlowStrategyTest {

    private static final TimeFrame TF = TimeFrame.H1;

    // --- computeSignal() : logique pure, sans réseau ni Spring -----------------------------

    @Nested
    @DisplayName("computeSignal()")
    class ComputeSignalTest {

        @Test
        @DisplayName("Flush confirmé : cascade de longs liquidés, prix en baisse, carnet aligné (ask-heavy) -> score négatif")
        void flushConfirme_bearish() {
            OrderFlowStrategy.OrderFlowScore result = OrderFlowStrategy.computeSignal(
                    900.0, 100.0, 1000.0,   // longLiquidated, shortLiquidated, total -> skew = +0.8
                    10000.0,                 // recentQuoteVolume -> ratio = 0.10 (>= 0.02)
                    -0.30,                   // imbalance : ask-heavy, aligné avec la cascade baissière
                    -0.05,                   // priceChangePct : baisse marquée
                    0.3, 0.02, 0.15,
                    0.02, 0.3
            );

            assertTrue(result.score() < 0, "score attendu négatif, obtenu " + result.score());
            assertTrue(result.reason().contains("confirmé"), "reason attendue mentionnant 'confirmé' : " + result.reason());
        }

        @Test
        @DisplayName("Flush confirmé : cascade de shorts liquidés, prix en hausse, carnet aligné (bid-heavy) -> score positif")
        void flushConfirme_bullish() {
            OrderFlowStrategy.OrderFlowScore result = OrderFlowStrategy.computeSignal(
                    100.0, 900.0, 1000.0,   // skew = -0.8 (shorts liquidés en masse)
                    10000.0,
                    0.30,                    // imbalance : bid-heavy, aligné avec la cascade haussière
                    0.05,
                    0.3, 0.02, 0.15,
                    0.02, 0.3
            );

            assertTrue(result.score() > 0, "score attendu positif, obtenu " + result.score());
        }

        @Test
        @DisplayName("Épuisement : cascade confirmée par le prix mais carnet opposé -> score fortement atténué, jamais inversé")
        void epuisement_dampenedScore() {
            OrderFlowStrategy.OrderFlowScore confirmed = OrderFlowStrategy.computeSignal(
                    900.0, 100.0, 1000.0, 10000.0,
                    -0.30, -0.05,
                    0.3, 0.02, 0.15, 0.02, 0.3
            );
            OrderFlowStrategy.OrderFlowScore exhausted = OrderFlowStrategy.computeSignal(
                    900.0, 100.0, 1000.0, 10000.0,
                    0.30,   // carnet opposé : bid-heavy alors que la cascade est baissière
                    -0.05,
                    0.3, 0.02, 0.15, 0.02, 0.3
            );

            assertTrue(exhausted.score() < 0, "toujours dans le sens de la cascade, jamais inversé franchement");
            assertTrue(Math.abs(exhausted.score()) < Math.abs(confirmed.score()),
                    "le score d'épuisement doit être strictement plus faible en valeur absolue que le flush confirmé");
            assertTrue(exhausted.reason().contains("épuisement"));
        }

        @Test
        @DisplayName("Cascade non significative (skew sous le seuil) -> neutre")
        void cascadeNonSignificative_neutral() {
            OrderFlowStrategy.OrderFlowScore result = OrderFlowStrategy.computeSignal(
                    550.0, 450.0, 1000.0,   // skew = 0.10, sous le seuil 0.3
                    10000.0,
                    -0.30, -0.05,
                    0.3, 0.02, 0.15, 0.02, 0.3
            );

            assertEquals(0.0, result.score(), 0.0001);
        }

        @Test
        @DisplayName("Ratio liquidations/volume trop faible -> neutre malgré un skew marqué")
        void liquidationVolumeRatioTooLow_neutral() {
            OrderFlowStrategy.OrderFlowScore result = OrderFlowStrategy.computeSignal(
                    900.0, 100.0, 1000.0,
                    1_000_000.0,   // ratio = 0.001, sous le seuil 0.02
                    -0.30, -0.05,
                    0.3, 0.02, 0.15, 0.02, 0.3
            );

            assertEquals(0.0, result.score(), 0.0001);
        }

        @Test
        @DisplayName("Cascade détectée mais direction du prix incohérente -> neutre")
        void inconsistentPriceDirection_neutral() {
            OrderFlowStrategy.OrderFlowScore result = OrderFlowStrategy.computeSignal(
                    900.0, 100.0, 1000.0, 10000.0,
                    -0.30,
                    0.05,   // prix en hausse alors que la cascade (longs liquidés) est baissière
                    0.3, 0.02, 0.15, 0.02, 0.3
            );

            assertEquals(0.0, result.score(), 0.0001);
            assertTrue(result.reason().contains("incohérente"));
        }
    }

    // --- accepts() ---------------------------------------------------------------------------

    @Test
    @DisplayName("accepts() vrai seulement avec exactement 1 ORDER_BOOK + 1 LIQUIDATIONS")
    void accepts_trueOnlyWithExactlyOneOfEachExpectedType() {
        OrderFlowStrategy strategy = new OrderFlowStrategy(new IndicatorRegistry(List.of()), mock(IndicatorEngine.class));

        assertTrue(strategy.accepts(paramsWith(IndicatorType.ORDER_BOOK, IndicatorType.LIQUIDATIONS)));
        assertFalse(strategy.accepts(paramsWith(IndicatorType.ORDER_BOOK)));
        assertFalse(strategy.accepts(paramsWith(IndicatorType.EMA, IndicatorType.ADX)));
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
    @DisplayName("evaluate() retourne notValid() quand un des 2 indicateurs est invalid")
    void evaluate_returnsNotValid_whenOneIndicatorInvalid() {
        IndicatorEngine engine = mock(IndicatorEngine.class);

        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.ORDER_BOOK)))
                .thenReturn(snapshotFor(IndicatorType.ORDER_BOOK, IndicatorResult.invalid()));
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.LIQUIDATIONS)))
                .thenReturn(snapshotFor(IndicatorType.LIQUIDATIONS, IndicatorResult.builder()
                        .valid(true)
                        .values(Map.of(LiquidationsIndicator.V_LONG, 900.0, LiquidationsIndicator.V_SHORT, 100.0, LiquidationsIndicator.V_TOTAL, 1000.0))
                        .build()));

        OrderFlowStrategy strategy = new OrderFlowStrategy(new IndicatorRegistry(List.of()), engine);

        StrategySignal signal = strategy.evaluate(marketContext(flatDataset()), fullParameters());

        assertFalse(signal.isValid());
    }

    @Test
    @DisplayName("evaluate() : bout en bout, flush confirmé baissier -> BEARISH, score négatif")
    void evaluate_endToEnd_flushConfirmeBaissier() {
        IndicatorEngine engine = mock(IndicatorEngine.class);

        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.ORDER_BOOK)))
                .thenReturn(snapshotFor(IndicatorType.ORDER_BOOK, IndicatorResult.builder()
                        .valid(true)
                        .value(-0.30)
                        .values(Map.of(OrderBookIndicator.V_IMBALANCE, -0.30, OrderBookIndicator.V_BID_VOLUME, 35.0, OrderBookIndicator.V_ASK_VOLUME, 65.0))
                        .build()));
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.LIQUIDATIONS)))
                .thenReturn(snapshotFor(IndicatorType.LIQUIDATIONS, IndicatorResult.builder()
                        .valid(true)
                        .values(Map.of(LiquidationsIndicator.V_LONG, 900.0, LiquidationsIndicator.V_SHORT, 100.0, LiquidationsIndicator.V_TOTAL, 1000.0))
                        .build()));

        OrderFlowStrategy strategy = new OrderFlowStrategy(new IndicatorRegistry(List.of()), engine);

        // Dataset en baisse marquée (>=2%) sur la fenêtre de lookback (10 bougies par défaut),
        // volume x close suffisamment faible pour que le ratio de liquidations dépasse le seuil.
        StrategySignal signal = strategy.evaluate(marketContext(decliningDataset()), fullParameters());

        assertTrue(signal.isValid());
        assertTrue(signal.getScore() < 0, "score attendu négatif, obtenu " + signal.getScore());
        assertEquals(SignalType.BEARISH, signal.getType());
    }

    private IndicatorSnapshot snapshotFor(IndicatorType type, IndicatorResult result) {
        return IndicatorSnapshot.builder()
                .indicatorType(type)
                .result(result)
                .build();
    }

    private StrategyParameters fullParameters() {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = new HashMap<>();
        for (IndicatorType type : List.of(IndicatorType.ORDER_BOOK, IndicatorType.LIQUIDATIONS)) {
            IndicatorParameters ip = IndicatorParameters.builder()
                    .indicatorType(type)
                    .numerics(Map.of())
                    .strings(Map.of(OrderFlowStrategy.P_TIME_FRAME_NAME, "H1"))
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
        return dataset(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("10"));
    }

    /** 11 bougies, prix passant de 100 à 94 (-6%, mouvement marqué), volume faible et constant
     *  (10 par bougie) : le volume-devise récent (somme volume x close sur la fenêtre de lookback)
     *  reste de l'ordre de quelques milliers, très inférieur au total liquidé (1000) utilisé dans
     *  le test qui l'invoque -> ratio de liquidations largement au-dessus du seuil (0.02). */
    private MarketDataset decliningDataset() {
        List<MarketData> candles = new ArrayList<>();
        BigDecimal price = new BigDecimal("100");
        BigDecimal step = new BigDecimal("0.6");
        for (int i = 0; i < 11; i++) {
            candles.add(MarketData.builder()
                    .timeFrame(TF)
                    .timestamp(Instant.now().minusSeconds((11 - i) * 3600L))
                    .pair("BTCUSDT")
                    .open(price)
                    .high(price)
                    .low(price)
                    .close(price)
                    .volume(new BigDecimal("10"))
                    .build());
            price = price.subtract(step);
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

    private MarketDataset dataset(BigDecimal open, BigDecimal close, BigDecimal volume) {
        List<MarketData> candles = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            candles.add(MarketData.builder()
                    .timeFrame(TF)
                    .timestamp(Instant.now().minusSeconds((11 - i) * 3600L))
                    .pair("BTCUSDT")
                    .open(open)
                    .high(open)
                    .low(close)
                    .close(close)
                    .volume(volume)
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
