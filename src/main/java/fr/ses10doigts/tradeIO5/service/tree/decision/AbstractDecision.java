package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionResult;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.DecisionAction;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.RiskProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.helper.DecisionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Classe de base pour les décisions concrètes.
 * Fournit l'orchestration standard des stratégies.
 */
public abstract class AbstractDecision implements Decision {
    private final Logger logger = LoggerFactory.getLogger(AbstractDecision.class);

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(DecisionParameters parameters) {
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
            DecisionContext context,
            DecisionParameters parameters
    ) {
        return parameters.getStrategies().stream()
                .map(key -> key.getStrategy().evaluate(context.getMarketContext(), key.getParameters()))
                .toList();
    }

    /**
     * Méthode template :
     * - les stratégies sont évaluées
     * - l'interprétation du résultat est laissée à l'implémentation
     */
    @Override
    public DecisionResult decide(DecisionContext context, DecisionParameters parameters) {
        List<StrategySignal> signals = evaluateStrategies(context, parameters);
        return interpretSignals(signals, context, parameters);
    }

    //
    // TOOLS
    //
    /**
     * Chaque décision définit sa propre logique métier ici.
     */
    protected abstract DecisionResult interpretSignals(
            List<StrategySignal> signals,
            DecisionContext context,
            DecisionParameters parameters
    );

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
     * Détermine le signal majoritaire parmi les stratégies.
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
     * Résout l'action finale selon le score pondéré, le signal majoritaire et le profil utilisateur.
     */
    protected DecisionAction resolveAction(double weightedScore,
                                           SignalType majoritySignal,
                                           RiskProfile profile) {

        // FIXME : Parametrize
        double LOW_PROFILE_THRESHOLD = 0.7;
        double MEDIUM_PROFILE_THRESHOLD = 0.35;
        double HIGH_PROFILE_THRESHOLD = 0.15;

        // Interprétation globale du score
        DecisionHelper.ConfidenceSignal globalSignal =
                DecisionHelper.scoreToConfidenceAndSignalType(weightedScore);

        SignalType scoreSignal = globalSignal.signal;
        double confidence = globalSignal.confidence;

        logger.debug("[weightedScore:{}, scoreSignal:{}, confidence:{}], majoritySignal:{}, profile:{}",
                weightedScore, scoreSignal, confidence, majoritySignal, profile);

        // Ambiguïté totale → HOLD
        if (scoreSignal == SignalType.NEUTRAL && majoritySignal == SignalType.NEUTRAL) {
            logger.debug("ScoreSignal == MajoritySignal == HOLD");
            return DecisionAction.HOLD;
        }

        return switch (profile) {
            case LOW -> {
                logger.debug("LOW Profile rules");
                // Profil conservateur :
                // - BUY/SELL uniquement si signal fort et cohérent
                if (confidence < LOW_PROFILE_THRESHOLD) {
                    logger.debug("confidence({}) < threshold({}) = HOLD", confidence, LOW_PROFILE_THRESHOLD);
                    yield DecisionAction.HOLD;
                }
                if (scoreSignal != majoritySignal){
                    logger.debug("scoreSignal({}) != majoritySignal({}) = HOLD", scoreSignal, majoritySignal);
                    yield DecisionAction.HOLD;
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
                    yield DecisionAction.HOLD;
                }
                logger.debug("Confidence({}) over threshold({}) so will use scoreSignal {}", confidence, MEDIUM_PROFILE_THRESHOLD, scoreSignal);
                yield mapSignalToAction(scoreSignal);
            }

            case HIGH -> {
                logger.debug("HIGH Profile rules");
                // Profil agressif :
                // - suit la direction dès qu’il y a une intention
                if (confidence < 0.15){
                    logger.debug("confidence({}) < threshold({}) = HOLD", confidence, HIGH_PROFILE_THRESHOLD);
                    yield DecisionAction.HOLD;
                }
                logger.debug("Confidence({}) over threshold({}) so will use scoreSignal {}", confidence, HIGH_PROFILE_THRESHOLD, scoreSignal);
                yield mapSignalToAction(scoreSignal);
            }
        };
    }

    protected DecisionAction mapSignalToAction(SignalType signalType) {
        return switch (signalType) {
            case BULLISH -> DecisionAction.BUY;
            case BEARISH -> DecisionAction.SELL;
            case NEUTRAL -> DecisionAction.HOLD;
        };
    }

    /**
     * Construit un DecisionResult avec toutes les informations nécessaires.
     */
    protected DecisionResult buildDecisionResult(DecisionAction action,
                                                 double weightedScore,
                                                 List<StrategySignal> signals,
                                                 RiskProfile profile) {
        DecisionResult result = new DecisionResult();
        result.setAction(action);
        result.setConfidence(Math.abs(weightedScore));
        result.setSignals(signals);
        result.setReason("WeightedScore=" + weightedScore + ", RiskProfile=" + profile);
        return result;
    }

}
