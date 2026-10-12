package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/**
 * Statut explicite + message lisible. Aucun solde n'est exposé. {@code quoteMember} : membre du groupe USD dans lequel
 * la dernière jambe d'achat est cotée (USDC, ou l'intermédiaire) ; {@code routeKey} : route d'achat (ex.
 * {@code USDC>PAXG}, {@code USDC>BTC>PAXG}). Nuls sinon.
 */
public record BindingCheckResult(BindingCheckStatus status, String message, String quoteMember, String routeKey) {

    public BindingCheckResult(BindingCheckStatus status, String message) {
        this(status, message, null, null);
    }

    /** Lecture du wallet autorisée (OK ou via intermédiaire). */
    public boolean isOk() {
        return status == BindingCheckStatus.OK || status == BindingCheckStatus.TRADABLE_VIA_INTERMEDIARY;
    }

    /** Exécution autorisée : paire directe ou route via intermédiaire choisi. */
    public boolean isExecutable() {
        return isOk();
    }

    /** Couple (actif, exchange) sans chemin spot sans fiat : exécution interdite. */
    public boolean isBlocked() {
        return status == BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT;
    }
}
