package fr.ses10doigts.tradeIO5.service.connector.trades;

import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Remplissage spot lu en lecture seule sur l'historique d'un exchange (ordres manuels et ordres TradeIO confondus).
 * {@code fee} est un coût positif exprimé dans {@code feeCcy} ; {@code clOrdId} peut être nul (Kraken).
 */
public record ImportedFill(String tradeId, String ordId, String clOrdId, String base, String quote, TradeSide side,
                           BigDecimal px, BigDecimal sz, BigDecimal fee, String feeCcy, Instant ts) {
}
