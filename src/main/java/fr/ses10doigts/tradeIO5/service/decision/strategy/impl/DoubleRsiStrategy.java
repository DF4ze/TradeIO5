package fr.ses10doigts.tradeIO5.service.decision.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.impl.RsiStrategySignalTypeWithConfidence;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.StrategyType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.service.decision.helper.StrategyHelper;
import fr.ses10doigts.tradeIO5.service.decision.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.IndicatorEngine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class DoubleRsiStrategy implements Strategy {
    private static final Logger logger = LoggerFactory.getLogger(DoubleRsiStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";
    public static final String P_BUY_THRESHOLD = "rsiBuyThreshold";
    public static final String P_SELL_THRESHOLD = "rsiSellThreshold";

    private final IndicatorEngine indicatorEngine;

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {

        // Récupération de la clé
        if(parameters.getIndicatorParameters().size() != 2){
            logger.error("Strategy {} needs 2 param", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 2 param");
        }

        List<RsiStrategySignalTypeWithConfidence> values = new ArrayList<>();
        // Pour tous les indicateurs donnés
        for ( Map.Entry<IndicatorKey, IndicatorParameters> entry : parameters.getIndicatorParameters().entrySet() ) {
            IndicatorKey indicatorKey = entry.getKey();
            IndicatorParameters rsiParams = entry.getValue();

            // Choix du TF depuis les paramètres de la stratégie
            TimeFrame tf = TimeFrame.valueOf(rsiParams.getStringParams().getOrDefault(P_TIME_FRAME_NAME, "H1"));

            IndicatorContext indicatorContext = IndicatorContext.builder()
                    .marketData(context.getSeries().get(tf))
                    .timestamp(context.getTimestamp())
                    .timeframe(tf)
                    .symbol(context.getSymbol())
                    .build();

            IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, rsiParams);

            // Stocker dans le MarketContext
            context.addIndicatorValue(indicatorKey, snapshot.getValue());

            // Interprétation selon seuils de la stratégie
            double value = snapshot.getValue().getValue();
            double buyThreshold = rsiParams.getNumericParams().getOrDefault(P_BUY_THRESHOLD, 30.0);
            double sellThreshold = rsiParams.getNumericParams().getOrDefault(P_SELL_THRESHOLD, 70.0);

            // Conversion en Signal + confiance
            RsiStrategySignalTypeWithConfidence signalConfidence =
                    StrategyHelper.evaluateRsiSinalWithConfidence(value, buyThreshold, sellThreshold);

            logger.debug("{} with parameters Buy {}, Sell {}, TF {} returns RSI {} as {} with {} confidence",
                    getName(), buyThreshold, sellThreshold, tf, value,
                        signalConfidence.getSignal(), signalConfidence.getConfidence());

            values.add(signalConfidence);
        }

        Map<SignalType, Integer> scoreSignal = new HashMap<>();
        double scoreConfidence = -1;
        SignalType prevSignal = SignalType.HOLD;
        int nb = 0;
        for (RsiStrategySignalTypeWithConfidence signal : values){
            nb ++;
            int count = 0;
            if(scoreSignal.containsKey(signal.getSignal())){
                count = scoreSignal.get(signal.getSignal());
            }
            count ++;

            if( scoreConfidence == -1 ) {
                scoreConfidence = signal.getConfidence();
                prevSignal = signal.getSignal();
            }
            else if( prevSignal == SignalType.BUY ){
                if( signal.getSignal() == SignalType.BUY )
                    scoreConfidence += signal.getConfidence();
                else
                    scoreConfidence -= signal.getConfidence();

            } else if (prevSignal == SignalType.SELL) {
                if( signal.getSignal() == SignalType.SELL )
                    scoreConfidence += signal.getConfidence();
                else
                    scoreConfidence -= signal.getConfidence();

            }else if (prevSignal == SignalType.HOLD) {
                if( signal.getSignal() == SignalType.HOLD )
                    scoreConfidence += signal.getConfidence();
                else
                    scoreConfidence -= signal.getConfidence();
            }

            scoreSignal.put(signal.getSignal(), count);
        }

        int max = 0;
        SignalType finalSignal = SignalType.HOLD;
        for (Map.Entry<SignalType, Integer>entry : scoreSignal.entrySet()){
            if( max < entry.getValue() ){
                max = entry.getValue();
                finalSignal = entry.getKey();
            }
        }

        return StrategySignal.builder()
                .strategyName(getName())
                .type(finalSignal)
                .confidence(scoreConfidence)
                .build();

    }

    @Override
    public Set<StrategyType> getType() {
        return new HashSet<>(List.of(StrategyType.ENTRY, StrategyType.EXIT));
    }
}
