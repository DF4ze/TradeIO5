package fr.ses10doigts.tradeIO5.model.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;

import java.time.Instant;

public interface PersistableEvent {
    String getId();           // unique pour l'event
    /**
     * {@code @JsonIgnore} (Palier 3, étape 4, même correctif que {@code ScenarioOwner.getId()}) :
     * {@code getTargetId()} délègue à un champ déjà sérialisé sous un autre nom dans chaque
     * implémentation ({@code decisionId} pour {@code DecisionEvent}, {@code scenarioId} pour {@code
     * ScenarioEvent}) — sans ce {@code @JsonIgnore}, Jackson sérialise une propriété {@code
     * "targetId"} en trop, que la désérialisation par constructeur canonique rejette ensuite
     * ("Unrecognized field").
     */
    @JsonIgnore
    String getTargetId();     // unique pour l'objet ciblé
    Instant getTimestamp();   // quand il est généré
    EventType getEventType(); // pour filtre / reconstruction
}
