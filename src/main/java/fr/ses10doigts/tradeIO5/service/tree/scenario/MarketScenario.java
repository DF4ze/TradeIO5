package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface MarketScenario {

    String              getId();
    ScenarioOwner       getOwner();
    ScenarioType        getType();
    Optional<String>    getSymbol();
    ScenarioState       getState();

    default ScenarioStatus getStatus() {
        return getState().getStatus();
    }

    default boolean isActive(Instant now, Duration duration) {
        return getState().isActive() && !getState().isExpired(now, duration);
    }

    void observe(OpinionSignal opinion, ScenarioContext context );

    Optional<ActionIntent> proposeIntent(Instant now);

    void enrichFrom(MarketScenario other, Instant now);



}
