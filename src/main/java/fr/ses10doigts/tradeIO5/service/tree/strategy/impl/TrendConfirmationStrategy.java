package fr.ses10doigts.tradeIO5.service.tree.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.LinearRegressionIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendAnalyzer;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Tendance confirmée" (étude {@code docs/etudes/etude-indicateur-trend-unifie.md} §3.1) — consomme
 * {@link TrendAnalyzer}, qui porte depuis l'Étape 8 de la roadmap Trend unifié (2026-09-24, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md}) la
 * régression linéaire multi-fenêtres à hystérésis. {@code SWING_STRUCTURE} et ADX ne font plus
 * partie de cette Strategy depuis ce même lot (retirés de {@link TrendAnalyzer}).
 * <p>
 * Contrat d'entrée : exactement 3 indicateurs {@code LINEAR_REGRESSION} (périodes court/moyen/long,
 * 7/14/30 par défaut) — même contrat que {@link RegressiveTrendStrategy}. Les constantes
 * {@code P_SLOPE_SCALE_FACTOR}/{@code P_WEIGHT_*}/{@code P_ALIGNMENT_ENABLED} et leurs valeurs par
 * défaut sont mutualisées depuis {@link RegressiveTrendStrategy} plutôt que redéfinies ici (règle du
 * projet : "mutualiser toute constante métier dupliquée", cf. {@code docs/CODING_RULES.md} ; demande
 * explicite du prompt Étape 8 §2.1 : "les constantes par défaut ne doivent exister qu'à un seul
 * endroit").
 * <p>
 * Le score continu est calculé par {@link TrendAnalyzer} directement à partir des bougies brutes du
 * dataset (pas des 3 valeurs {@code IndicatorResult} ponctuelles, qui ne suffisent pas à faire
 * tourner la machine à états d'hystérésis sur toute la timeline) ; les 3 indicateurs restent
 * néanmoins résolus via {@link IndicatorEngine} pour la traçabilité/cache
 * ({@code context.addIndicatorValue}), même patron que l'ancien contrat SWING_STRUCTURE+ADX.
 * <p>
 * <b>Cohérence type/score</b> (choix par défaut retenu au prompt §6 point 2) : le {@code type} du
 * signal est toujours dérivé du {@code regime} de l'hystérésis (UP/DOWN/RANGE), jamais redérivé du
 * score brut en aval — un état UP peut avoir {@code |score| < 1/6} (c'est tout l'intérêt de
 * l'hystérésis : ne pas repasser NEUTRAL à chaque petite oscillation), donc le score exposé dans
 * {@link StrategySignal} est ajusté pour qu'une éventuelle redérivation en aval via
 * {@link MarketOpinionHelper#scoreToConfidenceAndSignalType} retombe toujours sur le même type que
 * celui déjà déterminé par l'hystérésis.
 */
@Component
public class TrendConfirmationStrategy extends AbstractStrategy {
    private static final Logger logger = LoggerFactory.getLogger(TrendConfirmationStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";

    private final IndicatorEngine indicatorEngine;
    private final TrendAnalyzer trendAnalyzer = new TrendAnalyzer();

    public TrendConfirmationStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {

        if (parameters.getIndicatorParameters().size() != 3) {
            logger.error("Strategy {} needs 3 LINEAR_REGRESSION indicators (short/medium/long)", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 3 LINEAR_REGRESSION indicators");
        }

        boolean hasError = false;
        TimeFrame timeFrame = null;

        for (Map.Entry<IndicatorKey, IndicatorParameters> entry : parameters.getIndicatorParameters().entrySet()) {
            IndicatorKey indicatorKey = entry.getKey();
            IndicatorParameters indicatorParams = entry.getValue();

            if (indicatorKey.getType() != IndicatorType.LINEAR_REGRESSION) {
                logger.warn("{} : unexpected indicator type {} in indicatorParameters", getName(), indicatorKey.getType());
                hasError = true;
                continue;
            }

            TimeFrame tf = indicatorKey.getTimeFrame();
            timeFrame = tf;

            IndicatorContext indicatorContext = new IndicatorContext(
                    context.symbol(),
                    tf,
                    context.series().get(tf),
                    Map.of(),
                    context.clock()
            );

            IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, indicatorParams);

            if (!snapshot.getResult().isValid()) {
                logger.error("!---- LINEAR_REGRESSION (period={}) snapshot considered as INVALID --- Skipping!",
                        indicatorParams.getNumeric(LinearRegressionIndicator.P_PERIOD_NAME));
                hasError = true;
                continue;
            }

            // Traçabilité/debug (même rôle que sous l'ancien contrat SWING_STRUCTURE+ADX) : la
            // valeur réellement utilisée par TrendAnalyzer vient du calcul direct sur les bougies
            // brutes ci-dessous, pas de cet IndicatorResult ponctuel.
            context.addIndicatorValue(indicatorKey, snapshot.getResult());
        }

        if (hasError || timeFrame == null) {
            logger.error("{} : LINEAR_REGRESSION indicator(s) missing/invalid, cannot compute score", getName());
            return StrategySignal.notValid(getName(), "LINEAR_REGRESSION indicator(s) missing/invalid");
        }

        double slopeScaleFactor = parameters.getNumericParams().getOrDefault(
                RegressiveTrendStrategy.P_SLOPE_SCALE_FACTOR, RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR);
        double weightShort = parameters.getNumericParams().getOrDefault(
                RegressiveTrendStrategy.P_WEIGHT_SHORT, RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT);
        double weightMedium = parameters.getNumericParams().getOrDefault(
                RegressiveTrendStrategy.P_WEIGHT_MEDIUM, RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM);
        double weightLong = parameters.getNumericParams().getOrDefault(
                RegressiveTrendStrategy.P_WEIGHT_LONG, RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG);
        boolean alignmentEnabled = parameters.getBooleanParams().getOrDefault(
                RegressiveTrendStrategy.P_ALIGNMENT_ENABLED, RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED);

        MarketDataset dataset = context.series().get(timeFrame);
        List<MarketData> candles = dataset.getMarketDatas();

        TrendState trendState;
        try {
            trendState = trendAnalyzer.analyze(candles, slopeScaleFactor, weightShort, weightMedium, weightLong, alignmentEnabled);
        } catch (IllegalArgumentException e) {
            logger.error("{} : TrendAnalyzer could not compute a TrendState : {}", getName(), e.getMessage());
            return StrategySignal.notValid(getName(), "not enough candles for TrendAnalyzer : " + e.getMessage());
        }

        SignalType type = switch (trendState.regime()) {
            case UP -> SignalType.BULLISH;
            case DOWN -> SignalType.BEARISH;
            case RANGE -> SignalType.NEUTRAL;
        };

        // Cf. javadoc de classe (prompt §6 point 2) : en UP/DOWN, on garantit |score| >= barrière
        // (+ epsilon) pour qu'une redérivation en aval via MarketOpinionHelper reste cohérente avec
        // le type déjà déterminé par l'hystérésis, même quand le score brut est retombé sous la
        // barrière (ce qui est précisément l'intérêt de l'hystérésis).
        double score = switch (trendState.regime()) {
            case RANGE -> trendState.score();
            case UP -> Math.max(Math.abs(trendState.score()), MarketOpinionHelper.BARRIER + 1e-6);
            case DOWN -> -Math.max(Math.abs(trendState.score()), MarketOpinionHelper.BARRIER + 1e-6);
        };

        double confidence = MarketOpinionHelper.scoreToConfidenceAndSignalType(score).confidence;

        logger.debug("{} : regime={}, rawScore={}, force={}, hysteresisConfidence={} => score={}, confidence={}",
                getName(), trendState.regime(), trendState.score(), trendState.force(), trendState.confidence(),
                score, confidence);

        return StrategySignal.builder()
                .strategyName(getName())
                .valid(true)
                .type(type)
                .confidence(confidence)
                .score(score)
                .build();
    }

    @Override
    public Set<StrategyType> getType() {
        return Set.of(StrategyType.DIRECTIONAL);
    }

    @Override
    public boolean accepts(StrategyParameters parameters) {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = parameters.getIndicatorParameters();
        if (indicatorParameters == null || indicatorParameters.size() != 3) {
            return false;
        }

        long linearRegressionCount = indicatorParameters.values().stream()
                .filter(p -> p.getIndicatorType() == IndicatorType.LINEAR_REGRESSION)
                .count();

        return linearRegressionCount == 3;
    }

    /**
     * {@link TrendAnalyzer} a besoin d'au moins {@link TrendAnalyzer#MIN_CANDLES} bougies (fenêtre
     * longue de régression + warmup de l'hystérésis, cf. prompt Étape 8 §6 point 1) — supérieur au
     * {@code period} de la fenêtre longue seule (30) que renvoie
     * {@code LinearRegressionIndicator.getRequiredData}. Étendu ici plutôt que dans
     * {@code LinearRegressionIndicator}, dont le contrat générique ("required = period") reste
     * correct pour ses autres consommateurs (ex. {@link RegressiveTrendStrategy}, qui n'a pas besoin
     * du warmup de l'hystérésis).
     */
    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(StrategyParameters parameters) {
        Map<TimeFrame, Integer> required = new HashMap<>(super.getRequiredCandles(parameters));
        for (IndicatorKey key : parameters.getIndicatorParameters().keySet()) {
            required.merge(key.getTimeFrame(), TrendAnalyzer.MIN_CANDLES, Math::max);
        }
        return required;
    }
}
