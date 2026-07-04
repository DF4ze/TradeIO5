package fr.ses10doigts.tradeIO5.service.tree.event.engine;

import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import fr.ses10doigts.tradeIO5.model.dto.event.PersistableEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RequiredArgsConstructor
public class EventLogger {

    private static final Logger log = LoggerFactory.getLogger(EventLogger.class);

    private final List<Class<?>> eventTypesToLog;
    private final ExecutionMode mode;   // LIVE, BACKTEST, etc
    private final EventStoreRegistry registry;  // retourne le EventStore en fonction du mode

    @PostConstruct
    public void init(EventBus bus) {
        for (Class<?> eventType : eventTypesToLog) {
            bus.subscribe(eventType, this::handleEvent);
        }
    }

    private <T> void handleEvent(T event) {
        if (!(event instanceof PersistableEvent persistable)){
            log.debug("Refused event: Not Persistable");
            return; // ignore les autres
        }

        EventStore store = registry.forMode(mode);
        if (store != null) {
            store.append(persistable);  // le store sait comment gérer le type d’event
        }
    }
}
