package fr.ses10doigts.tradeIO5.service.tree.event.engine;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.model.dto.event.PersistableEvent;

import java.util.List;

public interface EventStore {

    void init();

    void append(PersistableEvent event);

    List<PersistableEvent> loadByType(EventType type);

    PersistableEvent loadById(String id);

    List<PersistableEvent>  loadByTargetId(String targetId);

}
