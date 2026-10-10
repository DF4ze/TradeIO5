package fr.ses10doigts.tradeIO5.service.execution.exchange;

/** Accusé d'acceptation d'un ordre par l'exchange (le remplissage se lit ensuite par {@code query} / {@code fills}). */
public record OrderAck(String clOrdId, String ordId) {
}
