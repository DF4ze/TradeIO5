package fr.ses10doigts.tradeIO5.service.tree.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.StrategyType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DoubleRsiStrategy extends AbstractStrategy {
    private static final Logger logger = LoggerFactory.getLogger(DoubleRsiStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";
    public static final String P_BUY_THRESHOLD = "rsiBuyThreshold";
    public static final String P_SELL_THRESHOLD = "rsiSellThreshold";

    private final IndicatorEngine indicatorEngine;

    public DoubleRsiStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }


    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {

        // Check validité
        if(parameters.getIndicatorParameters().size() != 2){
            logger.error("Strategy {} needs 2 param", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 2 param");
        }

        List<Double> values = new ArrayList<>();
        boolean hasError = false;
        // Pour tous les indicateurs donnés
        for ( Map.Entry<IndicatorKey, IndicatorParameters> entry : parameters.getIndicatorParameters().entrySet() ) {
            IndicatorKey indicatorKey = entry.getKey();
            IndicatorParameters rsiParams = entry.getValue();

            // Choix du TF depuis les paramètres de la stratégie
            TimeFrame tf = TimeFrame.valueOf(rsiParams.getStrings().getOrDefault(P_TIME_FRAME_NAME, "H1"));

            IndicatorContext indicatorContext = new IndicatorContext(
                    context.symbol(),
                    tf,
                    context.series().get(tf),
                    null,
                    context.clock()
            );

            IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, rsiParams);
            if( snapshot.getResult().isValid() ){
                logger.debug("Snapshot indicator value considered as VALID");
            }else{
                logger.error("!---- Snapshot indicator value considered as INVALID --- Skipping!");
                hasError = true;
                continue;
            }

            // Stocker dans le MarketContext
            context.addIndicatorValue(indicatorKey, snapshot.getResult());

            // Interprétation selon seuils de la stratégie
            double value = snapshot.getResult().getValue();
            double buyThreshold = rsiParams.getNumerics().getOrDefault(P_BUY_THRESHOLD, 30.0);
            double sellThreshold = rsiParams.getNumerics().getOrDefault(P_SELL_THRESHOLD, 70.0);

            // Conversion de valeur RSI vers score linéaire entre -1 (Sell) à +1 (Buy)
            double score = MarketOpinionHelper.computeRsiScore(value, buyThreshold, sellThreshold);

            logger.debug("{} with parameters Buy {}, Sell {}, TF {} returns RSI {} as {} score",
                    getName(), buyThreshold, sellThreshold, tf, value, score);

            values.add(score);
        }

          // on moyenne simplement les strategies...
        double score = values.stream().mapToDouble(Double::doubleValue).sum() /2;

        logger.debug("Average score: {}", score);

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(score);

        logger.debug("Average in Signal and confidence : {} at {}", confidenceSignal.signal, confidenceSignal.confidence);

        return StrategySignal.builder()
                .strategyName(getName())
                .valid(!hasError)
                .type(confidenceSignal.signal)
                .confidence(confidenceSignal.confidence)
                .score(score)
                .build();
    }

    @Override
    public Set<StrategyType> getType() {
        return new HashSet<>(List.of(StrategyType.ENTRY, StrategyType.EXIT));
    }
}
