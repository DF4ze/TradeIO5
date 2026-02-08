package fr.ses10doigts.tradeIO5.service.tree.event.engine;

import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventStoreRegistry {
    private final Logger log = LoggerFactory.getLogger(EventStoreRegistry.class);

    private final InMemoryEventStore inMemoryStore;
    private final JpaEventStore jpaStore;

    public EventStore forMode(ExecutionMode mode) {
        return switch (mode) {
            case LIVE -> jpaStore;
            case DEV, BACKTEST -> inMemoryStore;
        };
    }
}
