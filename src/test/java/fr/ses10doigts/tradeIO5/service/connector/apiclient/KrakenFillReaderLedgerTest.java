package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.KrakenFillReader.LedgerEntry;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KrakenFillReaderLedgerTest {

    private static final Instant T = Instant.parse("2026-11-08T10:00:00Z");

    private static LedgerEntry e(String ref, String type, String asset, String amount, String fee) {
        return new LedgerEntry(ref, type, asset, new BigDecimal(amount), new BigDecimal(fee), T);
    }

    @Test
    void achatDcaFiatEstUnAchat() {
        List<ImportedFill> fills = KrakenFillReader.toFills(List.of(
                e("R1", "spend", "ZEUR", "-100", "1"), e("R1", "receive", "PAXG", "0.025", "0")));
        assertEquals(1, fills.size());
        ImportedFill f = fills.getFirst();
        assertEquals("ledger-R1", f.tradeId());
        assertEquals("PAXG", f.base());
        assertEquals("EUR", f.quote());
        assertEquals(TradeSide.BUY, f.side());
        assertEquals(0, new BigDecimal("4000").compareTo(f.px()));
        assertEquals(0, new BigDecimal("0.025").compareTo(f.sz()));
        assertEquals(0, BigDecimal.ONE.compareTo(f.fee()));
    }

    @Test
    void venteContreStableEtIgnoreOrphelinsEtCryptoCrypto() {
        List<ImportedFill> fills = KrakenFillReader.toFills(List.of(
                e("S1", "spend", "PAXG", "-0.01", "0"), e("S1", "receive", "USDC", "40", "0.2"),
                e("O1", "spend", "ZEUR", "-50", "0"),
                e("C1", "spend", "XXBT", "-0.001", "0"), e("C1", "receive", "XETH", "0.03", "0")));
        assertEquals(1, fills.size());
        assertEquals(TradeSide.SELL, fills.getFirst().side());
        assertEquals("PAXG", fills.getFirst().base());
        assertEquals("USDC", fills.getFirst().quote());
    }
}
