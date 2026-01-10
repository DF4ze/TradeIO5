package fr.ses10doigts.tradeIO5.service.decision.helper;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.impl.RsiStrategySignalTypeWithConfidence;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;

public class StrategyHelper {

    public static final double MIN_CONFIDENCE = 0.3;

    public static RsiStrategySignalTypeWithConfidence evaluateRsiSinalWithConfidence(double rsi, double thresholdBuy, double thresholdSell) {
        SignalType signal;
        double confidence;
        double last = 1 - MIN_CONFIDENCE;

        if (rsi <= thresholdBuy) {
            signal = SignalType.BUY;
            // confiance linéaire entre ThresholdBuy -> 0.5 et 0 -> 1
            confidence = MIN_CONFIDENCE + last * (thresholdBuy - rsi) / thresholdBuy;

        } else if (rsi >= thresholdSell) {
            signal = SignalType.SELL;
            // confiance linéaire entre ThresholdSell -> 0.5 et 100 -> 1
            confidence = MIN_CONFIDENCE + last * (rsi - thresholdSell) / (100 - thresholdSell);

        } else {
            signal = SignalType.HOLD;
            double middle = (thresholdBuy + thresholdSell) / 2.0;
            // confiance décroissante linéaire : 1 au milieu, 0.5 aux seuils
            confidence = MIN_CONFIDENCE + last * (1 - Math.abs(rsi - middle) / (middle - thresholdBuy));
        }

        // clamp pour éviter que la confiance dépasse [0.5, 1]
        confidence = Math.max(MIN_CONFIDENCE, Math.min(1.0, confidence));

        return RsiStrategySignalTypeWithConfidence.builder()
                .signal(signal)
                .confidence(confidence)
                .build();
    }
}
