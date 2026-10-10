package fr.ses10doigts.tradeIO5.model.dto.execution;

import java.math.BigDecimal;

/**
 * Une jambe d'un chemin. {@code sz} en unités de la base, arrondie au {@code lotSz} (⌊⌋ BUY, ⌈⌉ SELL pour couvrir le
 * montant) ; {@code refPrice} = meilleur prix du côté pris, arrondi au tick. Coûts en % du montant.
 */
public record PathLeg(String instId, LegSide side, BigDecimal sz, BigDecimal refPrice, BigDecimal feePct,
                      BigDecimal spreadPct, BigDecimal slippagePct, BigDecimal costPct, boolean belowMin) {
}
