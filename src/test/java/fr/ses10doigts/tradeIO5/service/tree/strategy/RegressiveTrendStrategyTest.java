package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Piste "Tendances régressives" proposée par Clem (2026-09-19), cf. javadoc de
 * {@link RegressiveTrendStrategy}. Les bougies utilisées ici sont des séries linéaires
 * synthétiques (pas des cas réels calibrés) : la régression OLS sous-jacente est un calcul
 * déterministe simple (contrairement à SWING_STRUCTURE), donc les assertions se basent sur le
 * signe/l'ordre de grandeur attendu plutôt que sur des valeurs pré-validées hors-ligne.
 */
@DisplayName("Strategy - RegressiveTrend")
@SpringBootTest
class RegressiveTrendStrategyTest {
    private static final Logger logger = LoggerFactory.getLogger(RegressiveTrendStrategyTest.class);

    private static final TimeFrame TF = TimeFrame.D1;

    @Autowired
    private StrategyRegistry strategyRegistry;

    private static DomainClock clock;
    private static Instant baseTimestamp;

    @BeforeAll
    static void init() {
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
        baseTimestamp = Instant.parse("2024-01-01T00:00:00Z");
    }

    private static double[] linear(int n, double start, double step) {
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = start + step * i;
        }
        return closes;
    }

    /** 28 bougies déclinantes suivies de 7 bougies remontant doucement (contre-tendance courte). */
    private static double[] decliningThenShortRebound() {
        double[] closes = new double[35];
        for (int i = 0; i < 28; i++) {
            closes[i] = 300 - 2.0 * i; // 300 .. 246
        }
        for (int i = 28; i < 35; i++) {
            closes[i] = 246 + 2.0 * (i - 27); // 248 .. 260
        }
        return closes;
    }

    private static List<MarketData> candles(double[] closes) {
        List<MarketData> data = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            data.add(MarketData.builder()
                    .close(BigDecimal.valueOf(closes[i]))
                    .timestamp(baseTimestamp.plus(i, ChronoUnit.DAYS))
                    .build());
        }
        return data;
    }

    private static MarketContext context(double[] closes) {
        MarketDataset dataset = MarketDataset.builder()
                .marketDatas(candles(closes))
                .timeFrame(TF)
                .build();
        return new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                clock,
                Map.of(TF, dataset),
                new HashMap<>()
        );
    }

    private StrategySignal evaluate(double[] closes, StrategyParametersFactory.RegressiveTrendParam param) {
        StrategyParameters strategyParameters = StrategyParametersFactory.buildRegressiveTrendStrategyParam(param);

        Strategy strategy = strategyRegistry.get(RegressiveTrendStrategy.class.getSimpleName());
        StrategySignal signal = strategy.evaluate(context(closes), strategyParameters);
        logger.debug("signal={}", signal);
        return signal;
    }

    private StrategyParametersFactory.RegressiveTrendParam defaultParam() {
        return StrategyParametersFactory.RegressiveTrendParam.defaults(TF);
    }

    @Test
    @DisplayName("Tendance haussière soutenue sur les 3 fenêtres -> score fortement positif")
    void should_score_strongly_positive_on_sustained_uptrend() {
        double[] closes = linear(35, 100, 2.0);
        StrategySignal signal = evaluate(closes, defaultParam());

        assertTrue(signal.isValid(), "signal should be valid : " + signal.getReason());
        assertTrue(signal.getScore() > 0.6, "expected score > 0.6, got " + signal.getScore());
    }

    @Test
    @DisplayName("Tendance baissière soutenue sur les 3 fenêtres -> score fortement négatif (symétrique)")
    void should_score_strongly_negative_on_sustained_downtrend() {
        double[] closes = linear(35, 300, -2.0);
        StrategySignal signal = evaluate(closes, defaultParam());

        assertTrue(signal.isValid(), "signal should be valid : " + signal.getReason());
        assertTrue(signal.getScore() < -0.6, "expected score < -0.6, got " + signal.getScore());
    }

    @Test
    @DisplayName("Marché plat sur les 3 fenêtres -> score nul (r2=0 partout, aucune tendance à mesurer)")
    void should_score_zero_on_flat_market() {
        double[] closes = linear(35, 100, 0.0);
        StrategySignal signal = evaluate(closes, defaultParam());

        assertTrue(signal.isValid(), "signal should be valid : " + signal.getReason());
        assertEquals(0.0, signal.getScore());
    }

    @Test
    @DisplayName("Fenêtre courte en désaccord avec les fenêtres moyenne/longue -> score atténué (magnitude) par rapport au cas pleinement aligné")
    void should_dampen_score_when_windows_disagree() {
        double[] alignedDownClose = linear(35, 300, -2.0);
        double[] conflictingClose = decliningThenShortRebound();

        StrategySignal alignedSignal = evaluate(alignedDownClose, defaultParam());
        StrategySignal conflictingSignal = evaluate(conflictingClose, defaultParam());

        assertTrue(alignedSignal.isValid());
        assertTrue(conflictingSignal.isValid());
        assertTrue(Math.abs(conflictingSignal.getScore()) < Math.abs(alignedSignal.getScore()),
                "expected |conflicting score| < |aligned score| ; aligned=" + alignedSignal.getScore()
                        + " conflicting=" + conflictingSignal.getScore());
    }

    @Test
    @DisplayName("Historique insuffisant pour 2 des 3 fenêtres (period 14 et 30) -> signal invalide")
    void should_be_invalid_on_insufficient_history() {
        // 10 bougies : period=7 valide, period=14/30 invalides -> moins de 3 fenêtres valides.
        double[] closes = linear(10, 100, 1.0);
        StrategySignal signal = evaluate(closes, defaultParam());

        assertFalse(signal.isValid());
    }

    @Test
    @DisplayName("accepts(...) : accepte exactement 3xLINEAR_REGRESSION, refuse toute autre combinaison")
    void accepts_onlyExactlyThreeLinearRegression() {
        Strategy strategy = strategyRegistry.get(RegressiveTrendStrategy.class.getSimpleName());

        assertTrue(strategy.accepts(StrategyParametersFactory.buildRegressiveTrendStrategyParam(defaultParam())));

        // 2xLINEAR_REGRESSION + 1xEMA (bonne taille, mauvais type) : refusé.
        StrategyParameters wrongTypes = new StrategyParameters();
        IndicatorParameters lr1 = IndicatorParameters.builder()
                .indicatorType(IndicatorType.LINEAR_REGRESSION)
                .numerics(Map.of("period", 7.0)).strings(Map.of()).booleans(Map.of()).build();
        IndicatorParameters lr2 = IndicatorParameters.builder()
                .indicatorType(IndicatorType.LINEAR_REGRESSION)
                .numerics(Map.of("period", 14.0)).strings(Map.of()).booleans(Map.of()).build();
        IndicatorParameters ema = IndicatorParameters.builder()
                .indicatorType(IndicatorType.EMA)
                .numerics(Map.of("period", 30.0)).strings(Map.of()).booleans(Map.of()).build();
        wrongTypes.getIndicatorParameters().put(new IndicatorKey(IndicatorType.LINEAR_REGRESSION, TF, lr1), lr1);
        wrongTypes.getIndicatorParameters().put(new IndicatorKey(IndicatorType.LINEAR_REGRESSION, TF, lr2), lr2);
        wrongTypes.getIndicatorParameters().put(new IndicatorKey(IndicatorType.EMA, TF, ema), ema);
        assertFalse(strategy.accepts(wrongTypes), "2xLINEAR_REGRESSION + 1xEMA doit être refusé");

        // StrategyParameters vide : refusé.
        assertFalse(strategy.accepts(new StrategyParameters()));
    }
}
