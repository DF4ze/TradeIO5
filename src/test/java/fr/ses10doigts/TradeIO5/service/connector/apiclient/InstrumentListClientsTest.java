package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient.Listed;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Clients de liste d'instruments publics (OKX, Kraken)")
class InstrumentListClientsTest {

    private static final String OKX_BODY = """
            {"code":"0","msg":"","data":[
              {"instId":"BTC-USDC","baseCcy":"BTC","quoteCcy":"USDC","state":"live","minSz":"0.00001","lotSz":"0.00000001","tickSz":"0.1"},
              {"instId":"PAXG-USDT","baseCcy":"PAXG","quoteCcy":"USDT","state":"live","minSz":"0.0001","lotSz":"0.0001","tickSz":"0.1"},
              {"instId":"OLD-USDT","baseCcy":"OLD","quoteCcy":"USDT","state":"suspend","minSz":"1","lotSz":"1","tickSz":"0.01"},
              {"instId":"BAD-USDT","baseCcy":"BAD","quoteCcy":"USDT","state":"live","minSz":"","lotSz":"1","tickSz":"0.01"},
              {"instId":"NOCCY","state":"live"}
            ]}
            """;

    private static final String KRAKEN_BODY = """
            {"error":[],"result":{
              "XXBTZUSD":{"altname":"XBTUSD","base":"XXBT","quote":"ZUSD","lot_decimals":8,"pair_decimals":1,"ordermin":"0.0001","tick_size":"0.1","status":"online"},
              "XBTUSDC":{"altname":"XBTUSDC","base":"XXBT","quote":"USDC","lot_decimals":8,"pair_decimals":1,"ordermin":"0.0001","tick_size":"0.1","status":"online"},
              "PAXGUSD":{"altname":"PAXGUSD","base":"PAXG","quote":"ZUSD","lot_decimals":8,"pair_decimals":2,"ordermin":"0.003","status":"online"},
              "XBTUSDT.d":{"altname":"XBTUSDT.d","base":"XXBT","quote":"USDT","lot_decimals":8,"pair_decimals":1,"ordermin":"0.0001","status":"online"},
              "OLDUSD":{"altname":"OLDUSD","base":"OLD","quote":"ZUSD","lot_decimals":4,"pair_decimals":2,"ordermin":"1","status":"cancel_only"}
            }}
            """;

    @Test
    @DisplayName("OKX : paires live/suspendues parsées, entrées illisibles ignorées")
    void okxParse() {
        List<Listed> listed = OkxInstrumentListClient.parse(OKX_BODY);

        assertEquals(3, listed.size());
        Listed btc = listed.getFirst();
        assertEquals("BTC-USDC", btc.instId());
        assertEquals("BTC", btc.base());
        assertEquals("USDC", btc.quote());
        assertTrue(btc.live());
        assertEquals(0, new java.math.BigDecimal("0.00001").compareTo(btc.minSz()));
        assertEquals(0, new java.math.BigDecimal("0.1").compareTo(btc.tickSz()));
        assertFalse(listed.get(2).live());
    }

    @Test
    @DisplayName("OKX : erreur API, liste vide, réponse illisible => InstrumentLookupException")
    void okxFailures() {
        assertThrows(InstrumentLookupException.class, () -> OkxInstrumentListClient.parse("{\"code\":\"50011\",\"msg\":\"rate\"}"));
        assertThrows(InstrumentLookupException.class, () -> OkxInstrumentListClient.parse("{\"code\":\"0\",\"data\":[]}"));
        assertThrows(InstrumentLookupException.class, () -> OkxInstrumentListClient.parse("<html>"));
        assertThrows(InstrumentLookupException.class, () -> OkxInstrumentListClient.parse(""));
        assertThrows(InstrumentLookupException.class, () -> OkxInstrumentListClient.parse(
                "{\"code\":\"0\",\"data\":[{\"instId\":\"X\",\"state\":\"live\"}]}"));
    }

    @Test
    @DisplayName("OKX : un seul appel GET /api/v5/public/instruments?instType=SPOT, sans signature")
    void okxSingleCall() throws IOException {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " sign="
                    + exchange.getRequestHeaders().getFirst("OK-ACCESS-SIGN"));
            byte[] bytes = OKX_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            WebProvider provider = WebProvider.builder().code(WebProviderCode.OKX)
                    .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();

            List<Listed> listed = new OkxInstrumentListClient().fetchAll(provider);

            assertEquals(3, listed.size());
            assertEquals(List.of("GET /api/v5/public/instruments?instType=SPOT sign=null"), requests);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OKX : serveur injoignable => InstrumentLookupException")
    void okxUnreachable() {
        WebProvider provider = WebProvider.builder().code(WebProviderCode.OKX).apiBaseUrl("http://127.0.0.1:1").build();
        assertThrows(InstrumentLookupException.class, () -> new OkxInstrumentListClient().fetchAll(provider));
        assertThrows(InstrumentLookupException.class, () -> new OkxInstrumentListClient().fetchAll(null));
    }

    @Test
    @DisplayName("Kraken : noms normalisés, lot/tick/min, .d ignoré, cancel_only non live")
    void krakenParse() {
        List<Listed> listed = KrakenInstrumentListClient.parse(KRAKEN_BODY);

        assertEquals(4, listed.size());
        Listed btcUsd = listed.getFirst();
        assertEquals("BTC-USD", btcUsd.instId());
        assertEquals(0, new java.math.BigDecimal("0.00000001").compareTo(btcUsd.lotSz()));
        assertEquals(0, new java.math.BigDecimal("0.0001").compareTo(btcUsd.minSz()));
        assertEquals(0, new java.math.BigDecimal("0.1").compareTo(btcUsd.tickSz()));
        assertEquals("BTC-USDC", listed.get(1).instId());
        Listed paxg = listed.get(2);
        assertEquals("PAXG-USD", paxg.instId());
        assertEquals(0, new java.math.BigDecimal("0.01").compareTo(paxg.tickSz()));
        assertFalse(listed.get(3).live());
        assertTrue(listed.stream().noneMatch(l -> l.instId().contains("USDT")));
    }

    @Test
    @DisplayName("Kraken : erreur API, résultat vide, illisible => InstrumentLookupException")
    void krakenFailures() {
        assertThrows(InstrumentLookupException.class,
                () -> KrakenInstrumentListClient.parse("{\"error\":[\"EGeneral:Temporary lockout\"]}"));
        assertThrows(InstrumentLookupException.class, () -> KrakenInstrumentListClient.parse("{\"error\":[],\"result\":{}}"));
        assertThrows(InstrumentLookupException.class, () -> KrakenInstrumentListClient.parse("nope"));
    }

    @Test
    @DisplayName("OKX balance : codes 50100-50114 => CredentialRejectedException, 50011 non")
    void okxCredentialRejected() {
        assertThrows(CredentialRejectedException.class,
                () -> OkxBalanceReader.parseBalances("{\"code\":\"50113\",\"msg\":\"Invalid Sign\",\"data\":[]}"));
        assertThrows(CredentialRejectedException.class,
                () -> OkxBalanceReader.parseBalances("{\"code\":\"50105\",\"msg\":\"Passphrase incorrect\",\"data\":[]}"));
        try {
            OkxBalanceReader.parseBalances("{\"code\":\"50011\",\"msg\":\"Too many\",\"data\":[]}");
        } catch (CredentialRejectedException e) {
            throw new AssertionError("50011 n'est pas un rejet de clé", e);
        } catch (RuntimeException expected) {
            // BalanceUnavailableException simple
        }
    }
}
