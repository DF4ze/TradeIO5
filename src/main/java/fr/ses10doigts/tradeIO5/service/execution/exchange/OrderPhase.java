package fr.ses10doigts.tradeIO5.service.execution.exchange;

/** État d'un ordre vu de l'exchange. {@code CANCELED} couvre aussi le solde non rempli d'un IOC partiellement rempli. */
public enum OrderPhase {
    LIVE,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    UNKNOWN;

    public boolean terminal() {
        return this == FILLED || this == CANCELED;
    }
}
