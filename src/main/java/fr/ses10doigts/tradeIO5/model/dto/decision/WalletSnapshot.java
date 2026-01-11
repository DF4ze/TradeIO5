package fr.ses10doigts.tradeIO5.model.dto.decision;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Snapshot du portefeuille utilisateur.
 */
@Data
@Builder
public class WalletSnapshot {

    /** Balances par actif (ex: BTC -> 0.5, ETH -> 2.0) */
    private Map<String, Double> balances;

    /** Positions ouvertes (ex: symbole -> quantité) */
    private Map<String, Double> openPositions;

    /** Valeur totale du portefeuille en base (ex: EUR) */
    private double totalValue;

    /** Valeur totale investie (hors cash) */
    private double investedValue;
}
