package fr.ses10doigts.tradeIO5.model.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.DecisionEventCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.service.tree.decision.Decision;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/**
 * Palier 3, étape 4 : {@code onConstructor_ = @JsonCreator} ajouté sur le constructeur généré par
 * Lombok — sans lui, Jackson refuse de désérialiser (2 constructeurs visibles ici, celui-ci et le
 * constructeur métier {@code DecisionEvent(Decision, ...)} juste en dessous, aucun n'étant annoté :
 * "no Creators ... exist"). Même découverte/correctif que {@code ScenarioOwner}, cf. sa javadoc.
 */
@Getter
@RequiredArgsConstructor(onConstructor_ = @JsonCreator)
public class DecisionEvent implements PersistableEvent {

    // --- identité ---
    private final String id;
    private final String decisionId;
    private final EventType eventType = EventType.DECISION;

    // --- contexte métier ---
    private final String symbol;
    private final ScenarioOwner owner;
    private final DecisionType decisionType;

    // --- sémantique ---
    private final DecisionEventType decisionEventType;
    private final DecisionEventCause cause;

    // --- état ---
//    private final DecisionStatus before;
//    private final DecisionStatus after;

    private final Instant timestamp;

    public DecisionEvent( Decision decision, DecisionEventType eventType, DecisionEventCause eventCause, Instant now ){
        id = "[DecisionEvent]"+decision.getId();
        decisionId = decision.getId();
        symbol = decision.getSymbol();
        owner = decision.getOwner();
        decisionType = decision.getType();
        decisionEventType = eventType;
        cause = eventCause;
        timestamp = now;
    }

    @Override
    public String getTargetId() {
        return decisionId;
    }
}
