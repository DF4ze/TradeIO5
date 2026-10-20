package fr.ses10doigts.tradeIO5.model.dto.portfolio;

/**
 * Valeur et PnL d'un actif (dernier instantané, tous wallets). {@code investedNet} = achats − ventes (quantité x prix, frais
 * exclus) ; {@code pnl} = valeur − investi net ; {@code avgBuyPrice} = prix moyen d'achat. Valeurs nulles si le prix manque.
 */
public record AssetPerformanceDto(String assetSymbol, double quantity, Double price, Double value, double investedNet,
                                  Double pnl, Double pnlPct, Double avgBuyPrice) {
}
