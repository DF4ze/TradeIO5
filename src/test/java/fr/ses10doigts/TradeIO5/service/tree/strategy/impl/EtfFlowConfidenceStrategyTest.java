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
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.EtfFlowIndicator;
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

@DisplayName("Strategy - EtfFlowConfidence (ETF_FLOW)")
class EtfFlowConfidenceStrategyTest {

    private static final TimeFrame TF = TimeFrame.D1;
    // 50M significativité, x3 => atténuation max à 150M (defaults de la Strategy).
    private static final double FLOW_SIGNIFICANCE_THRESHOLD_USD = 50_000_000.0;
    private static final double MAGNITUDE_SCALE_FACTOR = 3.0;
    private static final double PRICE_MOVE_THRESHOLD = 0.02;

    // --- computeSignal() : logique pure, sans réseau ni Spring -----------------------------

    @Nested
    @DisplayName("computeSignal()")
    class ComputeSignalTest {

        @Test
        @DisplayName("Divergence : prix en hausse marquée mais flux ETF en sortie significative -> score négatif")
        void divergence_priceUpFlowOut_negativeScore() {
            EtfFlowConfidenceStrategy.EtfFlowScore result = EtfFlowConfidenceStrategy.computeSignal(
                    -100_000_000.0, 0.05,
                    FLOW_SIGNIFICANCE_THRESHOLD_USD, MAGNITUDE_SCALE_FACTOR, PRICE_MOVE_THRESHOLD
            );

            assertTrue(result.score() < 0, "score attendu négatif, obtenu " + result.score());
            assertTrue(result.reason().contains("divergence"));
        }

        @Test
        @DisplayName("Divergence : prix en baisse marquée mais flux ETF en entrée significative -> score négatif")
        void divergence_priceDownFlowIn_negativeScore() {
            EtfFlowConfidenceStrategy.EtfFlowScore result = EtfFlowConfidenceStrategy.computeSignal(
                    100_000_000.0, -0.05,
                    FLOW_SIGNIFICANCE_THRESHOLD_USD, MAGNITUDE_SCALE_FACTOR, PRICE_MOVE_THRESHOLD
            );

            assertTrue(result.score() < 0, "score attendu négatif, obtenu " + result.score());
        }

        @Test
        @DisplayName("Cohérence : prix en hausse et flux ETF en entrée -> score neutre (jamais de bonus)")
        void coherent_priceUpFlowIn_zeroScore() {
            EtfFlowConfidenceStrategy.EtfFlowScore result = EtfFlowConfidenceStrategy.computeSignal(
                    100_000_000.0, 0.05,
                    FLOW_SIGNIFICANCE_THRESHOLD_USD, MAGNITUDE_SCALE_FACTOR, PRICE_MOVE_THRESHOLD
            );

            assertEquals(0.0, result.score(), 0.0001);
            assertTrue(result.reason().contains("cohérent"));
        }

        @Test
        @DisplayName("Flux ETF sous le seuil de significativité -> neutre malgré un mouvement de prix marqué")
        void flowNotSignificant_zeroScore() {
            EtfFlowConfidenceStrategy.EtfFlowScore result = EtfFlowConfidenceStrategy.computeSignal(
                    -10_000_000.0, 0.05,
                    FLOW_SIGNIFICANCE_THRESHOLD_USD, MAGNITUDE_SCALE_FACTOR, PRICE_MOVE_THRESHOLD
            );

            assertEquals(0.0, result.score(), 0.0001);
        }

        @Test
        @DisplayName("Pas de mouvement de prix marqué -> neutre malgré un flux ETF significatif")
        void noMarkedPriceMove_zeroScore() {
            EtfFlowConfidenceStrategy.EtfFlowScore result = EtfFlowConfidenceStrategy.computeSignal(
                    -100_000_000.0, 0.005,
                    FLOW_SIGNIFICANCE_THRESHOLD_USD, MAGNITUDE_SCALE_FACTOR, PRICE_MOVE_THRESHOLD
            );

            assertEquals(0.0, result.score(), 0.0001);
        }

        @Test
        @DisplayName("Divergence maximale (3x le seuil) -> score plafonné à -1.0, jamais au-delà")
        void divergenceAtScaleCap_scoreClampedAtMinusOne() {
            EtfFlowConfidenceStrategy.EtfFlowScore result = EtfFlowConfidenceStrategy.computeSignal(
                    -1_000_000_000.0, 0.05,   // très au-delà de 150M (seuil x scale)
                    FLOW_SIGNIFICANCE_THRESHOLD_USD, MAGNITUDE_SCALE_FACTOR, PRICE_MOVE_THRESHOLD
            );

            assertEquals(-1.0, result.score(), 0.0001);
        }
    }

    // --- accepts() ---------------------------------------------------------------------------

    @Test
    @DisplayName("accepts() vrai seulement avec exactement 1 ETF_FLOW")
    void accepts_trueOnlyWithExactlyOneEtfFlow() {
        EtfFlowConfidenceStrategy strategy = new EtfFlowConfidenceStrategy(new IndicatorRegistry(List.of()), mock(IndicatorEngine.class));

        assertTrue(strategy.accepts(paramsWith(IndicatorType.ETF_FLOW)));
        assertFalse(strategy.accepts(paramsWith(IndicatorType.EMA)));
        assertFalse(strategy.accepts(paramsWith(IndicatorType.ETF_FLOW, IndicatorType.EMA)));
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
    @DisplayName("evaluate() retourne notValid() pour un symbole hors BTCUSDT/ETHUSDT (restriction §2.1/§9.5)")
    void evaluate_returnsNotValid_forSymbolOutsideWhitelist() {
        EtfFlowConfidenceStrategy strategy = new EtfFlowConfidenceStrategy(new IndicatorRegistry(List.of()), mock(IndicatorEngine.class));

        StrategySignal signal = strategy.evaluate(marketContext("SOLUSDT", decliningDataset()), fullParameters());

        assertFalse(signal.isValid());
    }

    @Test
    @DisplayName("evaluate() retourne notValid() quand ETF_FLOW est invalid")
    void evaluate_returnsNotValid_whenEtfFlowInvalid() {
        IndicatorEngine engine = mock(IndicatorEngine.class);
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.ETF_FLOW)))
                .thenReturn(snapshotFor(IndicatorResult.invalid()));

        EtfFlowConfidenceStrategy strategy = new EtfFlowConfidenceStrategy(new IndicatorRegistry(List.of()), engine);

        StrategySignal signal = strategy.evaluate(marketContext("BTCUSDT", decliningDataset()), fullParameters());

        assertFalse(signal.isValid());
    }

    @Test
    @DisplayName("evaluate() résout l'asset depuis le symbole (BTCUSDT -> BTC), pas depuis un paramètre "
            + "pré-rempli (§2.2) : divergence prix baissier / flux BTC entrant -> BEARISH")
    void evaluate_resolvesAssetFromSymbol_btc_divergence() {
        IndicatorEngine engine = mock(IndicatorEngine.class);
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.ETF_FLOW
                        && "BTC".equals(p.getString(EtfFlowIndicator.P_ASSET)))))
                .thenReturn(snapshotFor(IndicatorResult.builder().valid(true).value(200_000_000.0).build()));

        EtfFlowConfidenceStrategy strategy = new EtfFlowConfidenceStrategy(new IndicatorRegistry(List.of()), engine);

        // Dataset en baisse marquée + flux BTC en entrée (200M, cohérent avec "asset=BTC" résolu
        // depuis le symbole) -> divergence prix/flux -> score négatif.
        StrategySignal signal = strategy.evaluate(marketContext("BTCUSDT", decliningDataset()), fullParameters());

        assertTrue(signal.isValid());
        assertTrue(signal.getScore() < 0, "score attendu négatif, obtenu " + signal.getScore());
        assertEquals(SignalType.BEARISH, signal.getType());
    }

    @Test
    @DisplayName("evaluate() résout l'asset depuis le symbole (ETHUSDT -> ETH) : flux ETH cohérent avec "
            + "la baisse de prix -> neutre (aucune atténuation)")
    void evaluate_resolvesAssetFromSymbol_eth_coherent() {
        IndicatorEngine engine = mock(IndicatorEngine.class);
        when(engine.execute(any(), argThat(p -> p != null && p.getIndicatorType() == IndicatorType.ETF_FLOW
                        && "ETH".equals(p.getString(EtfFlowIndicator.P_ASSET)))))
                .thenReturn(snapshotFor(IndicatorResult.builder().valid(true).value(-200_000_000.0).build()));

        EtfFlowConfidenceStrategy strategy = new EtfFlowConfidenceStrategy(new IndicatorRegistry(List.of()), engine);

        // Dataset en baisse marquée + flux ETH en sortie (-200M, cohérent avec "asset=ETH" résolu
        // depuis le symbole) -> pas de divergence -> score neutre.
        StrategySignal signal = strategy.evaluate(marketContext("ETHUSDT", decliningDataset()), fullParameters());

        assertTrue(signal.isValid());
        assertEquals(0.0, signal.getScore(), 0.0001);
    }

    private IndicatorSnapshot snapshotFor(IndicatorResult result) {
        return IndicatorSnapshot.builder()
                .indicatorType(IndicatorType.ETF_FLOW)
                .result(result)
                .build();
    }

    private StrategyParameters fullParameters() {
        IndicatorParameters ip = IndicatorParameters.builder()
                .indicatorType(IndicatorType.ETF_FLOW)
                // "asset" volontairement absent ici (même si l'appelant le fournissait, evaluate()
                // ne doit jamais s'y fier, cf. §2.2) : ne teste rien sur ce champ, seule la valeur
                // recalculée depuis le symbole compte.
                .numerics(Map.of())
                .strings(Map.of(EtfFlowConfidenceStrategy.P_TIME_FRAME_NAME, "D1"))
                .booleans(Map.of())
                .build();

        Map<IndicatorKey, IndicatorParameters> indicatorParameters = new HashMap<>();
        indicatorParameters.put(new IndicatorKey(IndicatorType.ETF_FLOW, TF, ip), ip);

        StrategyParameters sp = new StrategyParameters();
        sp.setIndicatorParameters(indicatorParameters);
        return sp;
    }

    private MarketContext marketContext(String symbol, MarketDataset dataset) {
        return new MarketContext(
                symbol,
                new BigDecimal("42000"),
                Instant::now,
                Map.of(TF, dataset),
                new HashMap<>()
        );
    }

    /** 3 bougies D1, prix passant de 100 à 94 (-6%, mouvement marqué) : lookback=1 -> compare la
     *  dernière bougie à l'avant-dernière (94 vs 97 -> encore au-delà du seuil 2%). */
    private MarketDataset decliningDataset() {
        List<MarketData> candles = new ArrayList<>();
        BigDecimal price = new BigDecimal("100");
        BigDecimal step = new BigDecimal("3");
        for (int i = 0; i < 3; i++) {
            candles.add(MarketData.builder()
                    .timeFrame(TF)
                    .timestamp(Instant.now().minusSeconds((3 - i) * 86400L))
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
}
