package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/**
 * Statut explicite + message lisible. Aucun solde n'est exposé. {@code quoteMember} : membre du groupe USD dans lequel
 * l'actif est coté (OK, VIA_BRIDGE) ; {@code bridgePair} : paire de passerelle {@code <membre préféré>/<quoteMember>}
 * (VIA_BRIDGE uniquement). Nuls sinon.
 */
public record BindingCheckResult(BindingCheckStatus status, String message, String quoteMember, String bridgePair) {

    public BindingCheckResult(BindingCheckStatus status, String message) {
        this(status, message, null, null);
    }

    /** Lecture du wallet autorisée (OK ou via passerelle). */
    public boolean isOk() {
        return status == BindingCheckStatus.OK || status == BindingCheckStatus.TRADABLE_VIA_BRIDGE;
    }

    /** Exécution autorisée : paire directe uniquement tant que le chemin via passerelle n'est pas implémenté. */
    public boolean isExecutable() {
        return status == BindingCheckStatus.OK;
    }

    /** Couple (actif, exchange) sans chemin spot sans fiat : exécution interdite. */
    public boolean isBlocked() {
        return status == BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT;
    }
}
