package fr.ses10doigts.tradeIO5.service.tree.helper;


import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import lombok.AllArgsConstructor;
import lombok.ToString;

import java.time.Duration;
import java.time.Instant;

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

    /**
     * Normalise un pourcentage de variation (ex : delta j/j d'un indice) en score borné [-1,1]
     * (étude "nouvelles-opinions-indicateurs-non-branches" §2.2/§3) : interpolation linéaire
     * autour de zéro, saturée à ±1 au-delà de {@code scale} en valeur absolue. Même esprit que
     * l'interpolation ADX de {@code TrendConfirmationStrategy}, mais pour une variation relative
     * plutôt qu'un niveau absolu.
     *
     * @param scale échelle "mouvement typique" au-delà de laquelle le score sature ; doit être
     *              strictement positive, sinon retourne {@code 0.0} (posture conservatrice, pas
     *              de division par zéro).
     */
    public static double normalizeChangeScore(double changePct, double scale) {
        if (scale <= 0) {
            return 0.0;
        }
        return Math.clamp(changePct / scale, -1.0, 1.0);
    }

    /**
     * Score [-1,1] pour la croissance hebdomadaire de la capitalisation stablecoin (étude
     * "nouvelles-opinions-indicateurs-non-branches" §3) : proxy de liquidité crypto-native, injecté
     * dans {@code GlobalMarketOpinion} en complément de Fear&amp;Greed.
     *
     * @param totalPrevWeek peut être {@code null}/0 (réponse externe incomplète) : dans ce cas,
     *                      pas de croissance calculable, retourne {@code 0.0} (contribution neutre)
     *                      plutôt qu'une exception.
     */
    public static double computeStablecoinScore(double total, Double totalPrevWeek, double weeklyScale) {
        if (totalPrevWeek == null || totalPrevWeek == 0) {
            return 0.0;
        }
        double weeklyGrowthPct = (total - totalPrevWeek) / totalPrevWeek;
        return normalizeChangeScore(weeklyGrowthPct, weeklyScale);
    }

    /**
     * Facteur d'atténuation de confidence pour une valeur qui date (étude
     * "nouvelles-opinions-indicateurs-non-branches" §2.3) : les indices actions (SP500/NASDAQ) ne
     * tradant pas 24/7, une valeur restée figée depuis la dernière clôture ne doit pas peser autant
     * qu'une valeur fraîche. Décroissance continue au-delà de {@code staleThresholdHours} (jamais un
     * 0 brutal), même forme que {@link #computeSentimentShiftDampening}.
     *
     * @param lastTradeTimeEpochSeconds peut être {@code null} (timestamp non fourni par le
     *                                  provider) : dans ce cas, pas d'atténuation possible, retombe
     *                                  sur {@code 1.0}.
     */
    public static double computeStalenessDampening(
            Long lastTradeTimeEpochSeconds, Instant now, double staleThresholdHours) {
        if (lastTradeTimeEpochSeconds == null || staleThresholdHours <= 0) {
            return 1.0;
        }
        double ageHours = Duration.between(Instant.ofEpochSecond(lastTradeTimeEpochSeconds), now).toSeconds() / 3600.0;
        if (ageHours <= staleThresholdHours) {
            return 1.0;
        }
        return staleThresholdHours / ageHours;
    }

    public static double computeRsiScore(double value, double buyThreshold, double sellThreshold) {
        // plage centrale HOLD = [-1/6, +1/6]
        double holdMin = -1.0/6.0;
        double holdMax = 1.0/6.0;

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
