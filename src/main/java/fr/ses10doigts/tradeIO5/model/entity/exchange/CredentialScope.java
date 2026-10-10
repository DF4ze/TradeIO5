package fr.ses10doigts.tradeIO5.model.entity.exchange;

/**
 * Portée d'une clé API : {@code READ} (soldes, catalogue, frais ; clé d'un wallet) ou {@code TRADE} (ordres spot
 * uniquement, jamais Withdraw ; résolue par {@code TradeCredentialResolver}, chiffrée au repos, jamais lue par les lecteurs).
 */
public enum CredentialScope {
    READ,
    TRADE
}
