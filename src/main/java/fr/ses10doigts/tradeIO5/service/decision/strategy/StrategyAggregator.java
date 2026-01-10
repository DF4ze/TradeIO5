package fr.ses10doigts.tradeIO5.service.decision.strategy;


import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ⑤ StrategyAggregator
 *
 * - Pourquoi
 * Aucune stratégie ne doit avoir le dernier mot.
 *
 * - Responsabilité
 * agréger
 * lisser
 * neutraliser les extrêmes
 * détecter les conflits
 *
 * - Conceptuellement
 * “Globalement, que racontent les stratégies ?”
 */

@Component
public class StrategyAggregator {

    private final StrategyRegistry registry;

    public StrategyAggregator(StrategyRegistry registry) {
        this.registry = registry;
    }

    public AggregatedStrategySignal evaluate(MarketContext context) {

//        // TODO filter system
//        List<StrategySignal> signals = registry.getAll().stream()
//                .map(s -> s.evaluate(context))
//                .toList();
//
 //        return aggregate(signals);

        return null;
    }

    private AggregatedStrategySignal aggregate(List<StrategySignal> signals) {

        double buyScore = 0;
        double sellScore = 0;

        for (StrategySignal s : signals) {
            if (s.getType() == SignalType.BUY) {
                buyScore += s.getConfidence();
            }
            if (s.getType() == SignalType.SELL) {
                sellScore += s.getConfidence();
            }
        }

        boolean conflict = buyScore > 0 && sellScore > 0;

        SignalType finalSignal;
        double confidence;

        if (buyScore == 0 && sellScore == 0) {
            finalSignal = SignalType.HOLD;
            confidence = 0;
        } else if (buyScore > sellScore) {
            finalSignal = SignalType.BUY;
            confidence = normalize(buyScore, sellScore);
        } else {
            finalSignal = SignalType.SELL;
            confidence = normalize(sellScore, buyScore);
        }

        return AggregatedStrategySignal.builder()
                .finalSignal(finalSignal)
                .confidence(confidence)
                .conflictDetected(conflict)
                .signals(signals)
                .explanation(buildExplanation(buyScore, sellScore, conflict))
                .build();
    }

    private double normalize(double winner, double loser) {
        return winner / (winner + loser);
    }

    private String buildExplanation(double buy, double sell, boolean conflict) {
        if (buy == 0 && sell == 0) {
            return "All strategies are neutral";
        }
        if (conflict) {
            return "Conflicting signals detected (BUY vs SELL)";
        }
        return buy > sell ? "BUY pressure dominant" : "SELL pressure dominant";
    }
}

