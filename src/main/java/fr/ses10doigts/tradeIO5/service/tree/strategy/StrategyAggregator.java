package fr.ses10doigts.tradeIO5.service.tree.strategy;


import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyAggregatorParam;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>StrategyAggregator</h1>
 * <p>
 * - Pourquoi<br/>
 * Aucune stratégie ne doit avoir le dernier mot.
 * </p>
 * <p>
 * - Responsabilité<br/>
 * agréger<br/>
 * lisser<br/>
 * neutraliser les extrêmes<br/>
 * détecter les conflits<br/>
 * </p>
 * <p>
 * - Conceptuellement<br/>
 * “Globalement, que racontent les stratégies ?”<br/>
 * </p>
 */

@Component
public class StrategyAggregator {
    private static final Logger logger = LoggerFactory.getLogger(StrategyAggregator.class);

    public static AggregatedStrategySignal evaluate(MarketContext context, StrategyAggregatorParam param) {
        StrategySignal signal = param.getStrategy().evaluate(context, param.getParameters());
        List<StrategySignal> signals = new ArrayList<>(List.of(signal));

        return aggregate(signals);
    }

    public static AggregatedStrategySignal evaluate(MarketContext context, List<StrategyAggregatorParam> params) {
        List<StrategySignal> signals = params.stream()
                .map(p -> evaluateOne(context, p))
                .toList();
        return aggregate(signals);

    }

    private static StrategySignal evaluateOne( MarketContext context, StrategyAggregatorParam param ){
        return param.getStrategy().evaluate(context, param.getParameters());
    }

    private static AggregatedStrategySignal aggregate(List<StrategySignal> signals) {

        double totalScore = 0;
        boolean hasBuy = false;
        boolean hasSell = false;
        boolean hasError = false;
        double tier = 2.0 / 3.0;

        for (StrategySignal s : signals) {
            if( !s.isValid() ) {
                logger.error("Strat invalid : {}",s.getReason());
                hasError = true;
                totalScore = 0;
                break;
            }

            double score = s.getScore();
            totalScore += score;

            hasBuy |= score > tier;
            hasSell |= score < -tier;
        }

        boolean conflict = hasBuy && hasSell;

        MarketOpinionHelper.ConfidenceSignal cs = MarketOpinionHelper.scoreToConfidenceAndSignalType(totalScore);

        return AggregatedStrategySignal.builder()
                .score(totalScore)
                .finalSignal(cs.signal)
                .confidence(cs.confidence)
                .conflictDetected(conflict)
                .signals(signals)
                .explanation(buildExplanation(totalScore, conflict, hasError))
                .build();
    }



    private static String buildExplanation(double total, boolean conflict, boolean error) {
        if( error )
            return "!!Warning!! Some strategy failed";

        if (total == 0) {
            return "All strategies are neutral";
        }

        if (conflict) {
            return "Conflicting sources detected (BUY vs SELL)";
        }

        return total > 0 ? "Indicators BUY pressure dominant" : "Indicators SELL pressure dominant";
    }
}

