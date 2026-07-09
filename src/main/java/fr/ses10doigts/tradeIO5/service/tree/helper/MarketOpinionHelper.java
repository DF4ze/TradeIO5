package fr.ses10doigts.tradeIO5.service.tree.helper;


import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import lombok.AllArgsConstructor;
import lombok.ToString;

public class MarketOpinionHelper {

    /**
     * Mapping MarketIntentAction -> SignalType (étude "extension-risk-macro-external" §4.1),
     * pour une opinion EXTERNAL (LlmAdvice) qui doit produire un OpinionSignal au même
     * contrat que les opinions techniques. ADJUST/SUSPEND n'ont pas d'équivalent direct en
     * SignalType et retombent sur NEUTRAL : en pratique, le prompt d'OpenAIAdvisor
     * (AbstractAdvisor#expectedOutputBlock) ne demande au LLM que "BUY|SELL|HOLD", ces deux
     * valeurs ne sont donc pas produites aujourd'hui par ce chemin.
     */
    public static SignalType mapIntentActionToSignalType(MarketIntentAction action) {
        return switch (action) {
            case BUY -> SignalType.BULLISH;
            case SELL -> SignalType.BEARISH;
            case HOLD, ADJUST, SUSPEND -> SignalType.NEUTRAL;
        };
    }

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

    /**
     * Facteur d'atténuation de confidence pour un signal contrarian (ex. Fear&amp;Greed) dont la
     * valeur vient de bouger trop vite (étude "indicateurs-macro-externes", §2) : une hausse/baisse
     * brutale en zone extrême est plutôt le signe d'un retournement en cours que d'un signal
     * contrarian fiable à suivre tel quel.
     * <p>
     * Retourne {@code 1.0} (comportement inchangé) sauf si {@code now} est dans une zone extrême
     * ({@code now <= buyThreshold} ou {@code now >= sellThreshold}) <b>et</b> que
     * {@code |now - yesterday|} dépasse {@code deltaThreshold} : dans ce cas, retourne un facteur
     * dans {@code ]0, 1[} qui décroît continûment (jamais annulé brutalement à {@code 0}) à mesure
     * que le mouvement s'amplifie au-delà du seuil ({@code deltaThreshold / |delta|}, borné à 1 au
     * seuil lui-même).
     *
     * @param yesterday peut être {@code null} si la donnée n'est pas disponible (réponse externe
     *                  incomplète) : dans ce cas, pas d'atténuation possible, on retombe sur le
     *                  comportement actuel ({@code 1.0}).
     */
    public static double computeSentimentShiftDampening(
            double now, Double yesterday, double buyThreshold, double sellThreshold, double deltaThreshold) {
        if (yesterday == null) {
            return 1.0;
        }

        boolean extremeZone = now <= buyThreshold || now >= sellThreshold;
        if (!extremeZone) {
            return 1.0;
        }

        double delta = now - yesterday;
        double absDelta = Math.abs(delta);
        if (absDelta <= deltaThreshold) {
            return 1.0;
        }

        return deltaThreshold / absDelta;
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
        score = Math.clamp(score, -1.0, 1.0);

        return score;
    }


    @AllArgsConstructor
    @ToString
    public static class ConfidenceSignal {
        public double confidence;
        public SignalType signal;
    }
}
