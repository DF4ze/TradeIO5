package fr.ses10doigts.tradeIO5.service.tree.scenario.factory;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenarioImpl;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultScenarioFactory implements ScenarioFactory {

    @Override
    public List<MarketScenario> create(MarketOpinionResult opinion, ScenarioContext context) {
        ScenarioType type = classify(opinion);

        if (type == null) {
            return List.of();
        }

        return List.of(
                new MarketScenarioImpl(
                        type,
                        context.owner(),
                        context.symbol().orElse(null),
                        context.now(),
                        context.clock()
                )
        );
    }

    private ScenarioType classify(MarketOpinionResult o) {

        // CRASH / SURGE : mouvement violent
        if (o.score() < -0.85 && o.conviction() > 0.8) {
            return ScenarioType.CRASH;
        }
        if (o.score() > 0.85 && o.conviction() > 0.8) {
            return ScenarioType.SURGE;
        }

        // BREAKOUT
        if (o.weightedSignal() == SignalType.BULLISH
                && o.conviction() > 0.75
                && o.score() > 0.6) {
            return ScenarioType.BREAKOUT_UP;
        }

        if (o.weightedSignal() == SignalType.BEARISH
                && o.conviction() > 0.75
                && o.score() < -0.6) {
            return ScenarioType.BREAKOUT_DOWN;
        }

        // TREND
        if (o.majoritySignal() == SignalType.BULLISH
                && o.conviction() > 0.6) {
            return ScenarioType.TREND_UP;
        }

        if (o.majoritySignal() == SignalType.BEARISH
                && o.conviction() > 0.6) {
            return ScenarioType.TREND_DOWN;
        }

        // RANGE / VOLATILE
        if (o.conviction() < 0.4) {
            return ScenarioType.RANGE;
        }

        if (Math.abs(o.score()) > 0.7 && o.conviction() < 0.5) {
            return ScenarioType.VOLATILE;
        }

        return null;
    }
}
