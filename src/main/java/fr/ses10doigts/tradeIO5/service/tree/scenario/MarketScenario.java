package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface MarketScenario {

    ScenarioOwner getOwner();
    ScenarioType  getType();
    Optional<String> getSymbol();
    ScenarioState getState();

     void observe( MarketOpinionResult opinion, ScenarioContext context );

    Optional<ActionIntent> proposeIntent();


    void enrichFrom(MarketScenario other);

    default ScenarioStatus getStatus() {
        return getState().getStatus();
    }

    default boolean isActive(Instant now, Duration duration) {
        return getState().isActive() && !getState().isExpired(now, duration);
    }
}
