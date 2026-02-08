package fr.ses10doigts.tradeIO5.service.tree.scenario.factory;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultMarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;

import java.util.List;


public class ScenarioFactory{

    public static List<MarketScenario> create(
            OpinionSignal opinion,
            ScenarioContext context,
            EventBus eventBus
    ) {
        ScenarioType type = classify(opinion);

        if (type == null) {
            return List.of();
        }

        ScenarioDefinition definition = new ScenarioDefinition(
                type,
                context.owner(),
                context.symbol(),
                context.clock().now()
        );

        DefaultMarketScenario scenario = new DefaultMarketScenario(
                definition,
                eventBus
        );

        scenario.observe(opinion, context);

        return List.of( scenario );
    }

    private static ScenarioType classify(OpinionSignal o) {

        // CRASH / SURGE : mouvement violent
        if (o.score() < -0.85 && o.confidence() > 0.8) {
            return ScenarioType.CRASH;
        }
        if (o.score() > 0.85 && o.confidence() > 0.8) {
            return ScenarioType.SURGE;
        }

        // BREAKOUT
        if (o.weightedSignal() == SignalType.BULLISH
                && o.confidence() > 0.75
                && o.score() > 0.6) {
            return ScenarioType.BREAKOUT_UP;
        }

        if (o.weightedSignal() == SignalType.BEARISH
                && o.confidence() > 0.75
                && o.score() < -0.6) {
            return ScenarioType.BREAKOUT_DOWN;
        }

        // TREND
        if (o.majoritySignal() == SignalType.BULLISH
                && o.confidence() > 0.6) {
            return ScenarioType.TREND_UP;
        }

        if (o.majoritySignal() == SignalType.BEARISH
                && o.confidence() > 0.6) {
            return ScenarioType.TREND_DOWN;
        }

        // RANGE / VOLATILE
        if (o.confidence() < 0.4) {
            return ScenarioType.RANGE;
        }

        if (Math.abs(o.score()) > 0.7 && o.confidence() < 0.5) {
            return ScenarioType.VOLATILE;
        }

        return null;
    }
}
