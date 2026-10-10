package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Statut explicite + message lisible. Aucun solde n'est exposé. */
public record BindingCheckResult(BindingCheckStatus status, String message) {

    public boolean isOk() {
        return status == BindingCheckStatus.OK;
    }
}
