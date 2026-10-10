package fr.ses10doigts.tradeIO5.model.dto.execution;

import java.math.BigDecimal;

/**
 * Frais du compte pour un instrument, en <b>pourcentage du montant</b> (0,1 = 0,1 %), signés comme un coût :
 * positif = frais payé, négatif = rebate.
 */
public record FeeRate(BigDecimal makerPct, BigDecimal takerPct) {
}
