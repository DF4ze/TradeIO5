package fr.ses10doigts.tradeIO5.model.enumerate;

public enum WalletSource {
    EXCHANGE,         // Custodial - centralisé (Binance, Kraken…)
    NON_CUSTODIAL,    // Portefeuille local ou matériel (Ledger, MetaMask…)
    BANK_ACCOUNT,     // Compte bancaire (si tu gères du fiat)
    OTHER             // Autres (staking pool, protocole DeFi…)
}