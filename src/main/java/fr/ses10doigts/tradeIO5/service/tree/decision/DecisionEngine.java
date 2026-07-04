package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.DecisionCreatedCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.IntentCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionCandidate;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DecisionEngine {

    private final ScenarioOwner owner;
    private final DomainClock clock;
    //private final Set<String> symbols;
    private final EventBus eventBus;
    private final Map<String, Decision> activeDecisions;

    public DecisionEngine(ScenarioOwner owner, DomainClock clock, EventBus eventBus) {
        this.owner = owner;
        this.clock = clock;
       // this.symbols = symbols;
        this.eventBus = eventBus;
        activeDecisions = new ConcurrentHashMap();

        eventBus.subscribe(ScenarioEvent.class, this::onScenarioEvent);
    }

    private void onScenarioEvent(ScenarioEvent event) {

        if( !owner.isVisible(event.getOwner()) ||                                       // Que pour notre owner
                event.getScenarioEventType() != ScenarioEventType.ACTION_PROPOSED ||    // Que les scenarios terminés
                event.getSymbol().isEmpty()                                             // En sécurité : pas de Globaux
        ) return;

        DecisionCandidate candidate = mapToCandidate(event);

        Decision decision = createDecision(candidate);
        activeDecisions.put(decision.getSnapshot().decisionId(), decision);

        eventBus.publish(new DecisionEvent(
                decision,
                DecisionEventType.DECISION_CREATED,
                new DecisionCreatedCause(
                        decision.getId(),
                        "On Scenario Event"
                ),
                clock.now()
        ));

//        executor.execute(decision);
    }


    public Decision createDecision(DecisionCandidate candidate) {

        DecisionSnapshot snapshot = new DecisionSnapshot(
                UUID.randomUUID().toString(),
                candidate.symbol(),
                candidate.owner(),
                DecisionType.EXIT,
                clock.now()
        );

        ActionStep step = new ActionStep(
                UUID.randomUUID().toString(),
                candidate.action(),
                candidate.quantity()
        );

        return new Decision(
                snapshot,
                List.of(step),
                eventBus
        );
    }

    private DecisionCandidate mapToCandidate(ScenarioEvent event) {
        ActionIntent intent = ((IntentCause)event.getCause()).intent();
        return new DecisionCandidate(
                event.getSymbol().orElseThrow(() ->
                        new IllegalStateException("Decision requires a symbol")),
                DecisionType.EXIT, // TODO
                mapAction(intent),
                intent.confidence(),
                intent.quantity(),
                intent.reason(),
                event.getOwner(),
                clock.now()
        );
    }

    private ExecutionAction mapAction(ActionIntent intent) {
        return switch (intent.action()) {
            case BUY  -> ExecutionAction.BUY;
            case SELL -> ExecutionAction.SELL;
            default -> ExecutionAction.NO_OP;
        };
    }

}
