package fr.ses10doigts.tradeIO5.service.connector.trades;

import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Remplissage spot lu en lecture seule sur l'historique d'un exchange (ordres manuels et ordres TradeIO confondus).
 * {@code fee} est un coût positif exprimé dans {@code feeCcy} ; {@code clOrdId} peut être nul (Kraken). {@code recurring} : l'exchange
 * l'a exécuté comme achat récurrent automatique (DCA de l'exchange), détecté par le lecteur ; faux par défaut.
 */
public record ImportedFill(String tradeId, String ordId, String clOrdId, String base, String quote, TradeSide side,
                           BigDecimal px, BigDecimal sz, BigDecimal fee, String feeCcy, Instant ts, boolean recurring) {

    public ImportedFill(String tradeId, String ordId, String clOrdId, String base, String quote, TradeSide side,
                        BigDecimal px, BigDecimal sz, BigDecimal fee, String feeCcy, Instant ts) {
        this(tradeId, ordId, clOrdId, base, quote, side, px, sz, fee, feeCcy, ts, false);
    }

    public ImportedFill asRecurring() {
        return new ImportedFill(tradeId, ordId, clOrdId, base, quote, side, px, sz, fee, feeCcy, ts, true);
    }
}
