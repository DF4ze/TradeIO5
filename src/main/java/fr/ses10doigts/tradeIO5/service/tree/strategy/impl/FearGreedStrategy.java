package fr.ses10doigts.tradeIO5.service.tree.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Sentiment de marché large (Fear &amp; Greed), destinée à une opinion {@code GLOBAL}/{@code MACRO}
 * (étude précédente §3.3, étude "extension-risk-macro-external" §3.2) : contrairement à
 * {@link DoubleRsiStrategy}/{@link TrendConfirmationStrategy}, ne consomme qu'un indicateur
 * "valeur externe unique" ({@code FearAndGreedIndicator} ne lit jamais
 * {@code context.marketDataset()}/{@code context.symbol()}), et tolère donc un
 * {@code MarketContext#symbol()} nul (opinion non liée à un actif précis).
 * <p>
 * Lecture contrarian, comme discuté en étude : peur extrême (valeur basse) est traitée comme un
 * signal haussier (rebond probable), avidité extrême (valeur haute) comme un signal baissier.
 */
@Component
public class FearGreedStrategy extends AbstractStrategy {
    private static final Logger logger = LoggerFactory.getLogger(FearGreedStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";

    // Seuils propres à la Strategy (StrategyParameters.numericParams), comme
    // TrendConfirmationStrategy#P_ADX_LOW_THRESHOLD/P_ADX_HIGH_THRESHOLD.
    public static final String P_BUY_THRESHOLD = "fearGreedBuyThreshold";
    public static final String P_SELL_THRESHOLD = "fearGreedSellThreshold";

    private static final double DEFAULT_BUY_THRESHOLD = 25.0;   // <= 25 : extreme fear -> contrarian BUY
    private static final double DEFAULT_SELL_THRESHOLD = 75.0;  // >= 75 : extreme greed -> contrarian SELL

    private final IndicatorEngine indicatorEngine;

    public FearGreedStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {

        if (parameters.getIndicatorParameters().size() != 1) {
            logger.error("Strategy {} needs 1 param (FEAR_GREED)", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 1 param");
        }

        Map.Entry<IndicatorKey, IndicatorParameters> entry =
                parameters.getIndicatorParameters().entrySet().iterator().next();

        IndicatorKey indicatorKey = entry.getKey();
        IndicatorParameters fgParams = entry.getValue();

        TimeFrame tf = TimeFrame.valueOf(fgParams.getStrings().getOrDefault(P_TIME_FRAME_NAME, "H1"));

        // context.symbol()/context.series() peuvent être vides ici (opinion GLOBAL, pas liée à
        // un actif) : FearAndGreedIndicator ne les lit jamais, donc aucun des deux n'a besoin
        // d'être renseigné pour ce calcul (cf. étude, §3.2).
        IndicatorContext indicatorContext = new IndicatorContext(
                context.symbol(),
                tf,
                context.series() != null ? context.series().get(tf) : null,
                null,
                context.clock()
        );

        IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, fgParams);

        if (!snapshot.getResult().isValid() || snapshot.getResult().getValues() == null) {
            logger.error("{} : FEAR_GREED snapshot invalid", getName());
            return StrategySignal.notValid(getName(), "FEAR_GREED snapshot invalid");
        }

        Double now = snapshot.getResult().getValues().get("now");
        if (now == null) {
            logger.error("{} : FEAR_GREED snapshot missing 'now' value", getName());
            return StrategySignal.notValid(getName(), "FEAR_GREED snapshot missing 'now' value");
        }

        context.addIndicatorValue(indicatorKey, snapshot.getResult());

        double buyThreshold = parameters.getNumericParams().getOrDefault(P_BUY_THRESHOLD, DEFAULT_BUY_THRESHOLD);
        double sellThreshold = parameters.getNumericParams().getOrDefault(P_SELL_THRESHOLD, DEFAULT_SELL_THRESHOLD);

        // Réutilise computeRsiScore : malgré son nom, le mapping (valeur [0,100] + seuils buy/sell
        // -> score [-1,1]) n'a rien de spécifique au RSI, et c'est exactement le mapping
        // contrarian recherché ici.
        double score = MarketOpinionHelper.computeRsiScore(now, buyThreshold, sellThreshold);

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(score);

        logger.debug("{} : Fear&Greed(now={}) => score={}, signal={}, confidence={}",
                getName(), now, score, confidenceSignal.signal, confidenceSignal.confidence);

        return StrategySignal.builder()
                .strategyName(getName())
                .valid(true)
                .type(confidenceSignal.signal)
                .confidence(confidenceSignal.confidence)
                .score(score)
                .build();
    }

    @Override
    public Set<StrategyType> getType() {
        // StrategyType n'a pas de valeur "sentiment/global" : ENTRY reste le choix le moins
        // mauvais parmi ENTRY/EXIT/RISK pour un signal qui module un biais directionnel large.
        return Set.of(StrategyType.ENTRY);
    }

    @Override
    public boolean accepts(StrategyParameters parameters) {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = parameters.getIndicatorParameters();
        return indicatorParameters != null
                && indicatorParameters.size() == 1
                && indicatorParameters.values().stream()
                        .allMatch(p -> p.getIndicatorType() == IndicatorType.FEAR_GREED);
    }
}
