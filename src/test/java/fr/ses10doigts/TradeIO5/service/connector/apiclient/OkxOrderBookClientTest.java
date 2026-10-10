package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("OkxOrderBookClient : carnet public et repli ticker")
class OkxOrderBookClientTest {

    private static final String BOOKS = """
            {"code":"0","msg":"","data":[{"asks":[["100.1","2","0","1"],["100.2","5","0","2"]],
              "bids":[["99.9","3","0","1"],["99.8","4","0","1"]],"ts":"1700000000000"}]}
            """;
    private static final String TICKER = """
            {"code":"0","msg":"","data":[{"instId":"PAXG-USDT","last":"100","askPx":"100.1","askSz":"7","bidPx":"99.9","bidSz":"6"}]}
            """;

    private HttpServer server;
    private final AtomicReference<String> booksBody = new AtomicReference<>(BOOKS);
    private final AtomicReference<String> tickerBody = new AtomicReference<>(TICKER);
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String body = exchange.getRequestURI().getPath().endsWith("/books") ? booksBody.get() : tickerBody.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private WebProvider provider() {
        return WebProvider.builder().code(WebProviderCode.OKX).apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
    }

    @Test
    @DisplayName("Carnet : niveaux triés, estimation BOOK, requête books?instId=&sz= publique")
    void book() {
        BookResult result = new OkxOrderBookClient().fetchBook(provider(), "PAXG-USDT", 50);

        assertEquals(PathEstimation.BOOK, result.estimation());
        assertEquals(2, result.book().asks().size());
        assertEquals(0, new BigDecimal("100.1").compareTo(result.book().asks().getFirst().price()));
        assertEquals(0, new BigDecimal("3").compareTo(result.book().bids().getFirst().quantity()));
        assertEquals(List.of("GET /api/v5/market/books?instId=PAXG-USDT&sz=50"), requests);
    }

    @Test
    @DisplayName("Carnet en erreur ou vide => repli ticker (un niveau par côté), estimation TICKER")
    void tickerFallback() {
        booksBody.set("{\"code\":\"51001\",\"msg\":\"Instrument ID does not exist\",\"data\":[]}");
        BookResult result = new OkxOrderBookClient().fetchBook(provider(), "PAXG-USDT", 50);
        assertEquals(PathEstimation.TICKER, result.estimation());
        assertEquals(1, result.book().bids().size());
        assertEquals(0, new BigDecimal("99.9").compareTo(result.book().bids().getFirst().price()));
        assertEquals(0, new BigDecimal("7").compareTo(result.book().asks().getFirst().quantity()));

        booksBody.set("{\"code\":\"0\",\"data\":[{\"asks\":[],\"bids\":[]}]}");
        assertEquals(PathEstimation.TICKER, new OkxOrderBookClient().fetchBook(provider(), "PAXG-USDT", 50).estimation());
    }

    @Test
    @DisplayName("Carnet ET ticker indisponibles => InstrumentLookupException")
    void bothFail() {
        booksBody.set("{\"code\":\"50011\",\"msg\":\"rate\"}");
        tickerBody.set("{\"code\":\"0\",\"data\":[]}");
        assertThrows(InstrumentLookupException.class, () -> new OkxOrderBookClient().fetchBook(provider(), "PAXG-USDT", 50));

        tickerBody.set("<html>");
        assertThrows(InstrumentLookupException.class, () -> new OkxOrderBookClient().fetchBook(provider(), "PAXG-USDT", 50));
        assertThrows(InstrumentLookupException.class, () -> new OkxOrderBookClient().fetchBook(null, "PAXG-USDT", 50));
    }
}
