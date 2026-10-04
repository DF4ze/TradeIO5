package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculateur de Trend unifié — Étape 8 de la roadmap Trend unifié (2026-09-24, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md}) :
 * décision de Clem, après le walk-forward de l'Étape 7
 * ({@code docs/etudes/spec-composition-trend-unifie.md} §10), de porter la Trend entièrement par la
 * régression linéaire multi-fenêtres à hystérésis (candidate REG_H), à l'échelle des mouvements de
 * quelques semaines (swing) — pour que le futur Rainbow DCA capte ces opportunités.
 * {@code SWING_STRUCTURE} et ADX sont <b>retirés</b> de cet axe par ce même lot ({@code SWING_STRUCTURE}
 * reste un {@code IndicatorType} indépendant pour d'autres usages, {@code SwingStructureCalculator}
 * inchangé).
 * <p>
 * Service pur (même esprit que {@link LinearRegressionCalculator}/{@link SwingStructureCalculator}) :
 * ne dépend ni de {@code IndicatorResult}, ni de {@code IndicatorContext}, ni de Spring — pas de
 * {@code @Service}, c'est la responsabilité de l'appelant de fournir les bougies (architecture à 2
 * couches).
 * <p>
 * Algorithme (réglages figés par le walk-forward BTC D1 2017-2026, cf. spec §10.1/10.3, non
 * recalibrés dans ce lot) :
 * <ol>
 *   <li>3 régressions OLS des clôtures ({@link LinearRegressionCalculator}) sur {@value #SHORT_PERIOD}/
 *   {@value #MEDIUM_PERIOD}/{@value #LONG_PERIOD} bougies, combinées par
 *   {@link RegressiveTrendScoreCalculator} en un score continu [-1,1] par bougie ;</li>
 *   <li>Hystérésis ({@link RegressionHysteresisCalculator}, entrée ±1/6, sortie 0) sur la série
 *   chronologique de scores, pour produire un état discret {@link TrendRegime} par bougie.</li>
 * </ol>
 */
public class TrendAnalyzer {

    public static final int SHORT_PERIOD = 7;
    public static final int MEDIUM_PERIOD = 14;
    public static final int LONG_PERIOD = 30;

    /**
     * Cf. prompt §2.2 "Warmup" : la fenêtre longue de régression (30) plus une marge de 30 bougies
     * de score, valeur figée explicitement par le prompt (pas une valeur dérivée de
     * {@link RegressionHysteresisCalculator#MIN_SCORES}, qui garde sa propre garde indépendante).
     */
    public static final int MIN_CANDLES = LONG_PERIOD + 30;

    // Provisoire, cf. TrendState#confidence — pas encore calibré (10 ≈ durée médiane d'un état
    // UP/DOWN observée au walk-forward, cf. spec-composition-trend-unifie.md §10.4).
    private static final int CONFIDENCE_RUN_LENGTH_WINDOW = 10;

    private final LinearRegressionCalculator regressionCalculator = new LinearRegressionCalculator();

    /**
     * Réglages par défaut du walk-forward (cf. {@link RegressiveTrendScoreCalculator} et
     * {@code RegressiveTrendStrategy.DEFAULT_*}, seul et unique endroit où ils sont définis).
     */
    public TrendState analyze(List<MarketData> candles) {
        return analyzeTimeline(candles,
                RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG,
                RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED
        ).getLast();
    }

    /**
     * @throws IllegalArgumentException si {@code candles} est {@code null} ou compte moins de
     *                                  {@link #MIN_CANDLES} bougies — pas de valeur de repli
     *                                  silencieuse (même convention que {@link LinearRegressionCalculator}).
     */
    public TrendState analyze(
            List<MarketData> candles,
            double slopeScaleFactor, double weightShort, double weightMedium, double weightLong,
            boolean alignmentEnabled
    ) {
        return analyzeTimeline(candles, slopeScaleFactor, weightShort, weightMedium, weightLong, alignmentEnabled)
                .getLast();
    }

    /**
     * Timeline complète (un {@link TrendState} par bougie, à partir de la première bougie où les 3
     * régressions sont calculables) — même patron que
     * {@code LinearRegressionCalculator#computeTimeline}/{@code SwingStructureCalculator#computeTimeline} :
     * utile au benchmark de calibration et à la page de visualisation, pas seulement au sanity check
     * sur données réelles de ce lot.
     *
     * @throws IllegalArgumentException si {@code candles} est {@code null} ou compte moins de
     *                                  {@link #MIN_CANDLES} bougies.
     */
    public List<TrendState> analyzeTimeline(
            List<MarketData> candles,
            double slopeScaleFactor, double weightShort, double weightMedium, double weightLong,
            boolean alignmentEnabled
    ) {
        if (candles == null || candles.size() < MIN_CANDLES) {
            throw new IllegalArgumentException(
                    "not enough candles (" + (candles == null ? 0 : candles.size()) + ") for TrendAnalyzer : "
                            + MIN_CANDLES + " minimum (long window=" + LONG_PERIOD + " + warmup=30)");
        }

        List<LinearRegressionSnapshot> shortTimeline = regressionCalculator.computeTimeline(candles, SHORT_PERIOD);
        List<LinearRegressionSnapshot> mediumTimeline = regressionCalculator.computeTimeline(candles, MEDIUM_PERIOD);
        List<LinearRegressionSnapshot> longTimeline = regressionCalculator.computeTimeline(candles, LONG_PERIOD);

        List<Instant> timestamps = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            LinearRegressionSnapshot shortSnapshot = shortTimeline.get(i);
            LinearRegressionSnapshot mediumSnapshot = mediumTimeline.get(i);
            LinearRegressionSnapshot longSnapshot = longTimeline.get(i);
            if (shortSnapshot == null || mediumSnapshot == null || longSnapshot == null) {
                continue;
            }
            double score = RegressiveTrendScoreCalculator.computeScore(
                    new RegressiveTrendScoreCalculator.WindowInput(shortSnapshot.normalizedSlope(), shortSnapshot.r2()),
                    new RegressiveTrendScoreCalculator.WindowInput(mediumSnapshot.normalizedSlope(), mediumSnapshot.r2()),
                    new RegressiveTrendScoreCalculator.WindowInput(longSnapshot.normalizedSlope(), longSnapshot.r2()),
                    slopeScaleFactor, weightShort, weightMedium, weightLong, alignmentEnabled
            );
            timestamps.add(candles.get(i).getTimestamp());
            scores.add(score);
        }

        List<TrendRegime> regimeTimeline = RegressionHysteresisCalculator.computeTimeline(scores);

        List<TrendState> result = new ArrayList<>(regimeTimeline.size());
        TrendRegime previousRegime = null;
        int runLength = 0;
        int runStartIndex = 0;
        for (int i = 0; i < regimeTimeline.size(); i++) {
            TrendRegime regime = regimeTimeline.get(i);
            if (regime == previousRegime) {
                runLength++;
            } else {
                runLength = 1;
                runStartIndex = i;
            }
            previousRegime = regime;

            boolean bosJustOccurred = runLength == 1 && i > 0;
            Instant bosTimestamp = timestamps.get(runStartIndex);
            double confidence = Math.min(1.0, runLength / (double) CONFIDENCE_RUN_LENGTH_WINDOW);
            double score = scores.get(i);

            result.add(new TrendState(regime, score, Math.abs(score), confidence, bosJustOccurred, bosTimestamp));
        }
        return result;
    }
}
