package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.util.List;

/**
 * Performance d'un actif (tous wallets), calculée en prix moyen pondéré (PRU) sur les trades rejoués chronologiquement, frais inclus.
 * {@code avgBuyPrice} = PRU de la quantité détenue ; <b>latent</b> {@code pnl} = quantité x (cours − PRU), {@code pnlPct} = latent / coût
 * de la position ; <b>réalisé</b> {@code realizedPnl} = gains des ventes (produit net de frais − coût sorti), {@code realizedPct} = réalisé /
 * coût des quantités vendues ; {@code totalPnl} = réalisé + latent ; {@code fees} = frais cumulés en dollars ; {@code volume} = achats + ventes en dollars ; {@code bought} = achats cumulés (capital investi) ; {@code sells} = nombre de
 * ventes ; {@code incomplete} = une vente dépasse la quantité achetée connue (dépôt/transfert non suivi). {@code investedNet} = achats − ventes
 * (info). Le PRU diffère d'un provider à l'autre : {@code parts} donne la même mesure <b>par wallet</b>. Nuls sans cours ou sans achat.
 */
public record AssetPerformanceDto(String assetSymbol, double quantity, Double price, Double value, double investedNet,
                                  Double pnl, Double pnlPct, Double avgBuyPrice, double realizedPnl, Double realizedPct,
                                  Double totalPnl, double fees, double volume, double bought, int sells, boolean incomplete,
                                  List<Part> parts) {

    /** Mesure d'un actif sur un wallet (un provider). */
    public record Part(Long walletId, String walletName, String exchange, double quantity, Double avgBuyPrice, Double pnl, Double pnlPct, double realizedPnl,
                       Double realizedPct, Double totalPnl, double fees, double volume, double bought, int sells, boolean incomplete) {
    }
}
