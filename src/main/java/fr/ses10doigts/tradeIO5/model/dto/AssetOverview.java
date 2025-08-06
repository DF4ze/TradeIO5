package fr.ses10doigts.tradeIO5.model.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetOverview {
    private String asset;
    private BigDecimal quantity;
	private BigDecimal marketPrice;// prix actuel
	private BigDecimal value; // quantité * prix
	private BigDecimal averageBuyPrice; // prix d'achat moyen (pondéré)
	private BigDecimal averageSellPrice; // prix de vente moyen (pondéré)
	private BigDecimal totalInvested; // Somme investissement
	private BigDecimal totalSold; // Somme vendu
	private BigDecimal costBasis; // Prix de revient (Somme (qte*prix achat) - Somme(qte*prix vente))
	private String quoteCurrency; // USDC ou EUR
}