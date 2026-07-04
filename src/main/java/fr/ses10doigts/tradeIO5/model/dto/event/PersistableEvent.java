package fr.ses10doigts.tradeIO5.model.dto.event;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;

import java.time.Instant;

public interface PersistableEvent {
    String getId();           // unique pour l'event
    String getTargetId();     // unique pour l'objet ciblé
    Instant getTimestamp();   // quand il est généré
    EventType getEventType(); // pour filtre / reconstruction
}
