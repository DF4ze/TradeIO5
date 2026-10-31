package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeOrigin;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Provenance d'un trade : manuel ou TradeIO")
class ExchangeTradeImportServiceTest {

    private static ImportedFill fill(String ordId, String clOrdId) {
        return new ImportedFill("1", ordId, clOrdId, "BTC", "USDC", TradeSide.BUY, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "USDC", Instant.EPOCH);
    }

    @Test
    @DisplayName("clOrdId t5… ou ordre connu du plan ⇒ TradeIO ; sinon manuel")
    void origin() {
        Set<String> cl = Set.of("custom1"), ord = Set.of("O-77");
        assertEquals(TradeOrigin.TRADEIO, ExchangeTradeImportService.originOf(fill("x", "t5abc"), cl, ord));
        assertEquals(TradeOrigin.TRADEIO, ExchangeTradeImportService.originOf(fill("x", "custom1"), cl, ord));
        assertEquals(TradeOrigin.TRADEIO, ExchangeTradeImportService.originOf(fill("O-77", null), cl, ord));
        assertEquals(TradeOrigin.MANUAL, ExchangeTradeImportService.originOf(fill("other", null), cl, ord));
    }

    @Test
    @DisplayName("Ancien trade sans tag : id préfixé « EXCHANGE: » ⇒ TradeIO, sinon manuel ; tag explicite prioritaire")
    void legacyOrigin() {
        assertEquals(TradeOrigin.TRADEIO, HomeOverviewService.originOf(Transaction.builder().externalTransactionId("OKX:42").build()));
        assertEquals(TradeOrigin.MANUAL, HomeOverviewService.originOf(Transaction.builder().externalTransactionId("42").build()));
        assertEquals(TradeOrigin.MANUAL, HomeOverviewService.originOf(Transaction.builder().externalTransactionId("OKX:42").origin(TradeOrigin.MANUAL).build()));
    }
}
