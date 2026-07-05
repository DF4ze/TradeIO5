package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyAggregatorParam;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyAggregator;
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
     * Méthode template :
     * - les stratégies déclarées par les paramètres sont déléguées au {@link StrategyAggregator},
     *   seul responsable de l'agrégation (score, weightedSignal final, détection de conflit)
     * - l'interprétation du résultat agrégé est laissée à l'implémentation
     */
    @Override
    public void decide(OpinionContext context, MarketOpinionParameters parameters) {
        List<StrategyAggregatorParam> aggregatorParams = parameters.getStrategies().stream()
                .map(key -> new StrategyAggregatorParam(key.getStrategy(), key.getParameters()))
                .toList();

        AggregatedStrategySignal aggregatedSignal = StrategyAggregator.evaluate(context.marketContext(), aggregatorParams);

        interpretSignals(context, parameters, aggregatedSignal);
    }

    //
    // TOOLS
    //
    /**
     * Chaque décision définit sa propre logique métier ici.
     */
    abstract protected void interpretSignals(
            OpinionContext context,
            MarketOpinionParameters parameters,
            AggregatedStrategySignal aggregatedSignal);


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
