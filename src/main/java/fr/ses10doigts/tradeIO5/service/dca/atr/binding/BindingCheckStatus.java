package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Résultat métier de {@link BindingCheck} : un cas métier n'est jamais une exception. */
public enum BindingCheckStatus {
    OK,
    /** Wallet désactivé. */
    WALLET_DISABLED,
    /** Pas de credential, credential désactivée/incomplète, ou clé rejetée par l'exchange. */
    CREDENTIAL_INVALID,
    /** Aucun lecteur de soldes / vérificateur d'instrument pour l'exchange du wallet. */
    PROVIDER_UNSUPPORTED,
    /** Soldes illisibles (panne, réseau, réponse invalide). */
    BALANCE_UNAVAILABLE,
    /** La paire {@code <actif>/USDC} n'existe pas (ou est suspendue) sur l'exchange. */
    INSTRUMENT_MISSING,
    /** Existence de la paire non vérifiable (endpoint public injoignable). */
    INSTRUMENT_UNAVAILABLE
}
