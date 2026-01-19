package fr.ses10doigts.tradeIO5.service.tree.helper;


import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import lombok.AllArgsConstructor;
import lombok.ToString;

public class DecisionHelper {

    public static ConfidenceSignal scoreToConfidenceAndSignalType(double score){
        double barrier = 1.0 / 6.0;

        if (score >= -barrier && score < 0) {
            // linéaire : -1/6 -> 0, 0 -> 1
            return new ConfidenceSignal(
                    Math.abs((score + barrier) / barrier),
                    SignalType.NEUTRAL
            );
        } else if (score >= 0 && score <= barrier) {
            // linéaire : 0 -> +1/6 , 1 -> 0
            return new ConfidenceSignal(
                    Math.abs(score / barrier),
                    SignalType.NEUTRAL
            );
        } else {
            // en dehors des barrières : directement
            return new ConfidenceSignal(
                    Math.abs(score),
                    score > 0 ? SignalType.BULLISH : SignalType.BEARISH
            );
        }
    }

    public static double computeRsiScore(double value, double buyThreshold, double sellThreshold) {
        // plage centrale HOLD = [-1/6, +1/6]
        double holdMin = -1.0/6.0;
        double holdMax = +1.0/6.0;

        // derniers tiers pour BUY et SELL
        double upperTierMin = 5.0/6.0;
        double upperTierMax = 1.0;
        double lowerTierMin = -1.0;
        double lowerTierMax = -5.0/6.0;

        double score;

        if (value < buyThreshold) {
            // BUY
            // map linéairement [0..buyThreshold] → [upperTierMin .. upperTierMax]
            score = upperTierMin + (value / buyThreshold) * (upperTierMax - upperTierMin);
        } else if (value > sellThreshold) {
            // SELL
            // map linéairement [sellThreshold..100] → [lowerTierMax .. lowerTierMin]
            score = lowerTierMax + ((value - sellThreshold) / (100.0 - sellThreshold)) * (lowerTierMin - lowerTierMax);
        } else {
            // HOLD
            // map linéairement [buyThreshold..sellThreshold] → [holdMin..holdMax]
            score = holdMin + ((value - buyThreshold) / (sellThreshold - buyThreshold)) * (holdMax - holdMin);
        }

        // clamp au cas où
        score = Math.max(-1.0, Math.min(1.0, score));

        return score;
    }


    @AllArgsConstructor
    @ToString
    public static class ConfidenceSignal {
        public double confidence;
        public SignalType signal;
    }
}
