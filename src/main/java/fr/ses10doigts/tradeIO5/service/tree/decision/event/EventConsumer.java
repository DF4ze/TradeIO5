package fr.ses10doigts.tradeIO5.service.tree.decision.event;

public interface EventConsumer<E> {
    void apply(E event);
}