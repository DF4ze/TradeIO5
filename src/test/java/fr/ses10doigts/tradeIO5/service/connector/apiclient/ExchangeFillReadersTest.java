package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Lecteurs d'historique des remplissages OKX / Kraken : décodage")
class ExchangeFillReadersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("OKX : fill spot décodé (frais positif, clOrdId vide ⇒ nul)")
    void okxFill() throws Exception {
        ImportedFill f = OkxFillReader.parse(MAPPER.readTree("{\"instId\":\"OKB-USDT\",\"tradeId\":\"77\",\"ordId\":\"9\",\"clOrdId\":\"\","
                + "\"side\":\"sell\",\"fillPx\":\"50.5\",\"fillSz\":\"2\",\"fee\":\"-0.1\",\"feeCcy\":\"USDT\",\"ts\":\"1761865200000\"}"));
        assertEquals("OKB", f.base());
        assertEquals("USDT", f.quote());
        assertEquals(TradeSide.SELL, f.side());
        assertEquals(new BigDecimal("0.1"), f.fee());
        assertNull(f.clOrdId());
        assertEquals(Instant.ofEpochMilli(1761865200000L), f.ts());
    }

    @Test
    @DisplayName("Kraken : paire XXBTZUSD ⇒ BTC/USD, PAXGXBT ⇒ PAXG/BTC, frais dans la cotation")
    void krakenPairs() throws Exception {
        ImportedFill a = KrakenFillReader.parse("T1", MAPPER.readTree("{\"pair\":\"XXBTZUSD\",\"time\":1761865200.5,\"type\":\"buy\","
                + "\"price\":\"68000\",\"vol\":\"0.001\",\"fee\":\"0.1\",\"ordertxid\":\"O1\"}"));
        assertEquals("BTC", a.base());
        assertEquals("USD", a.quote());
        assertEquals("O1", a.ordId());
        assertEquals("USD", a.feeCcy());
        ImportedFill b = KrakenFillReader.parse("T2", MAPPER.readTree("{\"pair\":\"PAXGXBT\",\"time\":1761865200,\"type\":\"sell\","
                + "\"price\":\"0.04\",\"vol\":\"0.5\",\"fee\":\"0.00001\",\"ordertxid\":\"O2\"}"));
        assertEquals("PAXG", b.base());
        assertEquals("BTC", b.quote());
        assertEquals(TradeSide.SELL, b.side());
    }

    @Test
    @DisplayName("Kraken : paire séparée « PAXG/USDC » ⇒ base PAXG propre (jamais « PAXG/ »)")
    void krakenSlashPair() throws Exception {
        ImportedFill f = KrakenFillReader.parse("T3", MAPPER.readTree("{\"pair\":\"PAXG/USDC\",\"time\":1761865200,\"type\":\"buy\","
                + "\"price\":\"4000\",\"vol\":\"0.1\",\"fee\":\"0.1\",\"ordertxid\":\"O3\"}"));
        assertEquals("PAXG", f.base());
        assertEquals("USDC", f.quote());
    }
}
