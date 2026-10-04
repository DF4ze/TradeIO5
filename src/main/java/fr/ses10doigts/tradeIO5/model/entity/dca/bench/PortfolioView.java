package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/**
 * Vue minimale d'un portefeuille pour le moteur Rainbow : cash en USDC (seul stablecoin) et quantité
 * détenue par actif. Implémentée par {@link RainbowLiveMockWallet} ; un vrai wallet pourra
 * l'implémenter plus tard sans toucher au moteur. Ne présuppose pas « 1 wallet = 1 actif ».
 */
public interface PortfolioView {

    /** Cash disponible en USDC. */
    double cash();

    /** Quantité détenue de l'actif (symbole nu : BTC, ETH, PAXG) ; 0 si l'actif n'est pas détenu. */
    double quantity(String assetSymbol);
}
