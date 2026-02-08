package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Classe de base pour les décisions concrètes.
 * Fournit l'orchestration standard des stratégies.
 */
public abstract class AbstractMarketOpinion implements MarketOpinion {
    private final Logger logger = LoggerFactory.getLogger(AbstractMarketOpinion.class);

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(MarketOpinionParameters parameters) {
        return parameters.getStrategies().stream()
                .map(key -> key.getStrategy().getRequiredCandles(key.getParameters()))
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        Math::max
                ));
    }

    /**
     * Exécute toutes les stratégies associées à la décision.
     */
    protected List<StrategySignal> evaluateStrategies(
            OpinionContext context,
            MarketOpinionParameters parameters
    ) {
        return parameters.getStrategies().stream()
                .map(key -> key.getStrategy().evaluate(context.marketContext(), key.getParameters()))
                .toList();
    }

    /**
     * Méthode template :
     * - les stratégies sont évaluées
     * - l'interprétation du résultat est laissée à l'implémentation
     */
    @Override
    public void decide(OpinionContext context, MarketOpinionParameters parameters) {
        List<StrategySignal> signals = evaluateStrategies(context, parameters);

        double weightedScore = calculateWeightedScore(signals);
        SignalType majoritySignal = determineMajoritySignal(signals);
        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(weightedScore);

        interpretSignals(signals, context, parameters, weightedScore, majoritySignal, confidenceSignal);
    }

    //
    // TOOLS
    //
    /**
     * Chaque décision définit sa propre logique métier ici.
     */
    abstract protected void interpretSignals(
            List<StrategySignal> signals,
            OpinionContext context,
            MarketOpinionParameters parameters,
            double weightedScore,
            SignalType majoritySignal,
            MarketOpinionHelper.ConfidenceSignal confidenceSignal) ;



    /**
     * Calcule le score moyen pondéré par la confiance.
     */
    protected double calculateWeightedScore(List<StrategySignal> signals) {
        return signals.stream()
                .mapToDouble(s -> s.getScore() * s.getConfidence())
                .average()
                .orElse(0.0);
    }

    /**
     * Détermine le weightedSignal majoritaire parmi les stratégies.
     */
    protected SignalType determineMajoritySignal(List<StrategySignal> signals) {
        long buy = signals.stream().filter(s -> s.getType() == SignalType.BULLISH).count();
        long sell = signals.stream().filter(s -> s.getType() == SignalType.BEARISH).count();
        long hold = signals.stream().filter(s -> s.getType() == SignalType.NEUTRAL).count();

        if (buy > sell && buy > hold) return SignalType.BULLISH;
        if (sell > buy && sell > hold) return SignalType.BEARISH;
        return SignalType.NEUTRAL;
    }


    /**
     * Résout l'action finale selon le score pondéré, le weightedSignal majoritaire et le profil utilisateur.
     */
 /*   protected MarketAction resolveAction(double weightedScore,
                                         SignalType majoritySignal,
                                         RiskProfile profile) {

        // FIXME : Parametrize
        double LOW_PROFILE_THRESHOLD = 0.7;
        double MEDIUM_PROFILE_THRESHOLD = 0.35;
        double HIGH_PROFILE_THRESHOLD = 0.15;

        // Interprétation globale du score
        MarketOpinionHelper.ConfidenceSignal globalSignal =
                MarketOpinionHelper.scoreToConfidenceAndSignalType(weightedScore);

        SignalType scoreSignal = globalSignal.weightedSignal;
        double confidence = globalSignal.confidence;

        logger.debug("[weightedScore:{}, scoreSignal:{}, confidence:{}], majoritySignal:{}, profile:{}",
                weightedScore, scoreSignal, confidence, majoritySignal, profile);

        // Ambiguïté totale → HOLD
        if (scoreSignal == SignalType.NEUTRAL && majoritySignal == SignalType.NEUTRAL) {
            logger.debug("ScoreSignal == MajoritySignal == HOLD");
            return MarketAction.HOLD;
        }

        return switch (profile) {
            case LOW -> {
                logger.debug("LOW Profile rules");
                // Profil conservateur :
                // - BUY/SELL uniquement si weightedSignal fort et cohérent
                if (confidence < LOW_PROFILE_THRESHOLD) {
                    logger.debug("confidence({}) < threshold({}) = HOLD", confidence, LOW_PROFILE_THRESHOLD);
                    yield MarketAction.HOLD;
                }
                if (scoreSignal != majoritySignal){
                    logger.debug("scoreSignal({}) != majoritySignal({}) = HOLD", scoreSignal, majoritySignal);
                    yield MarketAction.HOLD;
                }
                logger.debug("Confidence({}) over threshold({}) so will use scoreSignal {}", confidence, LOW_PROFILE_THRESHOLD, scoreSignal);
                yield mapSignalToAction(scoreSignal);
            }

            case MEDIUM -> {
                logger.debug("MEDIUM Profile rules");
                // Profil équilibré :
                // - suit le score global s’il est clair
                if (confidence < MEDIUM_PROFILE_THRESHOLD){
                    logger.debug("confidence({}) < threshold({}) = HOLD", confidence, MEDIUM_PROFILE_THRESHOLD);
                    yield MarketAction.HOLD;
                }
                logger.debug("Confidence({}) over threshold({}) so will use scoreSignal {}", confidence, MEDIUM_PROFILE_THRESHOLD, scoreSignal);
                yield mapSignalToAction(scoreSignal);
            }

            case HIGH -> {
                logger.debug("HIGH Profile rules");
                // Profil agressif :
                // - suit la majoritySignal dès qu’il y a une intention
                if (confidence < 0.15){
                    logger.debug("confidence({}) < threshold({}) = HOLD", confidence, HIGH_PROFILE_THRESHOLD);
                    yield MarketAction.HOLD;
                }
                logger.debug("Confidence({}) over threshold({}) so will use scoreSignal {}", confidence, HIGH_PROFILE_THRESHOLD, scoreSignal);
                yield mapSignalToAction(scoreSignal);
            }
        };
    }

    protected MarketAction mapSignalToAction(SignalType signalType) {
        return switch (signalType) {
            case BULLISH -> MarketAction.BUY;
            case BEARISH -> MarketAction.SELL;
            case NEUTRAL -> MarketAction.HOLD;
        };
    }

  */



}
