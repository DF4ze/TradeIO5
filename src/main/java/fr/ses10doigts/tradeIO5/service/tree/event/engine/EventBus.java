package fr.ses10doigts.tradeIO5.service.tree.event.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new HashMap<>();

    public <T> void subscribe(Class<T> eventType, Consumer<T> consumer) {
        log.debug("New Subscription: {}, {}", eventType, consumer);

        subscribers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(consumer);
    }

    public <T> void publish(T event) {
        int delivered = 0;

        for (Map.Entry<Class<?>, List<Consumer<?>>> entry : subscribers.entrySet()) {
            Class<?> subscribedType = entry.getKey();

            if (subscribedType.isAssignableFrom(event.getClass())) {
                for (Consumer<?> c : entry.getValue()) {
                    @SuppressWarnings("unchecked")
                    Consumer<T> consumer = (Consumer<T>) c;
                    consumer.accept(event);
                    delivered++;
                }
            }
        }

        log.debug("{} Sub on New Publication: {}", delivered, event);
    }
}
