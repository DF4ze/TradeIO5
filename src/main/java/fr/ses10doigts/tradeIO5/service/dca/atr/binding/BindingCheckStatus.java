package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Résultat métier de {@link BindingCheck} : un cas métier n'est jamais une exception. */
public enum BindingCheckStatus {
    /** Paire directe {@code <actif>/<membre de cotation du groupe USD>} tradable. */
    OK,
    /** Wallet désactivé. */
    WALLET_DISABLED,
    /** Pas de credential, credential désactivée/incomplète, ou clé rejetée par l'exchange. */
    CREDENTIAL_INVALID,
    /** Aucun lecteur de soldes / vérificateur d'instrument pour l'exchange du wallet. */
    PROVIDER_UNSUPPORTED,
    /** Soldes illisibles (panne, réseau, réponse invalide). */
    BALANCE_UNAVAILABLE,
    /** Achat via un intermédiaire non stable choisi (ex : {@code USDC -> BTC -> PAXG} sur Kraken) : deux jambes, route mémorisée sur le lien. */
    TRADABLE_VIA_INTERMEDIARY,
    /** Aucun chemin spot sans monnaie fiat (ni intermédiaire choisi) vers l'actif : toute exécution est bloquée. */
    NOT_TRADABLE_WITHOUT_FIAT,
    /** Existence de la paire non vérifiable (endpoint public injoignable). */
    INSTRUMENT_UNAVAILABLE
}
