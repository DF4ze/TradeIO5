package fr.ses10doigts.tradeIO5.model.dto.event.decision;

public interface EventConsumer<E> {
    void apply(E event);
}