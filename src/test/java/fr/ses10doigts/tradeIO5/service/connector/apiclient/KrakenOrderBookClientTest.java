package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("KrakenOrderBookClient : parsing de Depth et du repli Ticker")
class KrakenOrderBookClientTest {

    @Test
    @DisplayName("Depth : niveaux [prix, volume, horodatage] triés du meilleur au moins bon")
    void parsesDepth() {
        OrderBookSnapshot book = KrakenOrderBookClient.parseDepth("""
                {"error":[],"result":{"PAXGXBT":{
                  "asks":[["0.08005","1.500",1700000000],["0.08010","3.000",1700000001]],
                  "bids":[["0.07995","2.000",1700000002],["0.07990","4.000",1700000003]]}}}""");

        assertEquals(2, book.asks().size());
        assertEquals(0, new BigDecimal("0.08005").compareTo(book.asks().getFirst().price()));
        assertEquals(0, new BigDecimal("1.5").compareTo(book.asks().getFirst().quantity()));
        assertEquals(0, new BigDecimal("0.07995").compareTo(book.bids().getFirst().price()));
    }

    @Test
    @DisplayName("Ticker : meilleur bid/ask, un niveau par côté")
    void parsesTicker() {
        OrderBookSnapshot book = KrakenOrderBookClient.parseTicker("""
                {"error":[],"result":{"PAXGXBT":{"a":["0.08005","1","1.500"],"b":["0.07995","2","2.000"]}}}""");

        assertEquals(1, book.asks().size());
        assertEquals(0, new BigDecimal("0.08005").compareTo(book.asks().getFirst().price()));
        assertEquals(0, new BigDecimal("2.000").compareTo(book.bids().getFirst().quantity()));
    }

    @Test
    @DisplayName("Erreur API, résultat vide, carnet vide ou illisible => InstrumentLookupException")
    void errors() {
        assertThrows(InstrumentLookupException.class, () -> KrakenOrderBookClient.parseDepth("{\"error\":[\"EQuery:Unknown asset pair\"]}"));
        assertThrows(InstrumentLookupException.class, () -> KrakenOrderBookClient.parseDepth("{\"error\":[],\"result\":{}}"));
        assertThrows(InstrumentLookupException.class,
                () -> KrakenOrderBookClient.parseDepth("{\"error\":[],\"result\":{\"X\":{\"asks\":[],\"bids\":[]}}}"));
        assertThrows(InstrumentLookupException.class,
                () -> KrakenOrderBookClient.parseDepth("{\"error\":[],\"result\":{\"X\":{\"asks\":[[\"a\",\"1\"]],\"bids\":[[\"1\",\"1\"]]}}}"));
        assertThrows(InstrumentLookupException.class, () -> KrakenOrderBookClient.parseTicker("nope"));
    }
}
