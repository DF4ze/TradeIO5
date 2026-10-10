package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un remplissage. {@code fee} est un <b>coût positif</b> (OKX renvoie un signe négatif pour un frais payé), exprimé dans
 * {@code feeCcy} (la base à l'achat, la cotation à la vente sur OKX).
 */
public record Fill(String tradeId, String ordId, String clOrdId, String instId, StepSide side, BigDecimal px, BigDecimal sz,
                   BigDecimal fee, String feeCcy, Instant ts) {
}
