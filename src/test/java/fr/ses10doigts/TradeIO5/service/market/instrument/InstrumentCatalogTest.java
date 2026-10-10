package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.ExchangeInstrumentRepository;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@DisplayName("InstrumentCatalog : cache DB des paires, 1 appel public/jour, jamais vidé")
class InstrumentCatalogTest {

    static class FakeClient implements InstrumentListClient {
        int calls;
        List<Listed> response = new ArrayList<>();
        RuntimeException failure;

        @Override
        public WebProviderCode getProviderCode() {
            return WebProviderCode.OKX;
        }

        @Override
        public List<Listed> fetchAll(WebProvider provider) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    static final FakeClient CLIENT = new FakeClient();
    static final FixedDomainClock CLOCK = new FixedDomainClock(Instant.parse("2026-10-10T08:00:00Z"));

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ExchangeInstrumentRepository repository;
    private InstrumentCatalog catalog;

    private final WebProvider okx = WebProvider.builder().code(WebProviderCode.OKX).apiBaseUrl("http://okx").build();

    private static InstrumentListClient.Listed listed(String base, String quote, boolean live) {
        return new InstrumentListClient.Listed(base + "-" + quote, base, quote, live, new BigDecimal("0.0001"),
                new BigDecimal("0.0001"), new BigDecimal("0.1"));
    }

    @BeforeEach
    void reset() {
        catalog = new InstrumentCatalog(repository, List.of(CLIENT), CLOCK, transactionManager, Duration.ofHours(24));
        CLIENT.calls = 0;
        CLIENT.failure = null;
        CLIENT.response = new ArrayList<>(List.of(listed("BTC", "USDC", true), listed("PAXG", "USDT", true),
                listed("USDC", "USDT", true), listed("OLD", "USDT", false)));
        CLOCK.set(Instant.parse("2026-10-10T08:00:00Z"));
    }

    @Test
    @DisplayName("Premier accès : un seul fetch ; accès suivants frais : plus aucun appel réseau")
    void fetchOnceWhenFresh() {
        assertTrue(catalog.isTradable(okx, "BTC", "USDC"));
        assertTrue(catalog.isTradable(okx, "PAXG", "USDT"));
        assertFalse(catalog.isTradable(okx, "PAXG", "USDC"));
        assertEquals(3, catalog.liveInstruments(okx).size());

        assertEquals(1, CLIENT.calls);
        assertEquals(4, repository.count());
    }

    @Test
    @DisplayName("Paire suspendue => non négociable ; supports() reflète les clients")
    void suspendedAndSupports() {
        assertFalse(catalog.isTradable(okx, "OLD", "USDT"));
        assertTrue(catalog.supports(WebProviderCode.OKX));
        assertFalse(catalog.supports(WebProviderCode.KRAKEN));
        assertFalse(catalog.supports(null));
    }

    @Test
    @DisplayName("Après 24 h : nouveau fetch ; paires disparues retirées, nouvelles ajoutées")
    void refreshAfterOneDay() {
        catalog.isTradable(okx, "BTC", "USDC");
        CLIENT.response = new ArrayList<>(List.of(listed("BTC", "USDC", true), listed("ETH", "USDC", true)));

        CLOCK.advance(Duration.ofHours(23));
        assertTrue(catalog.isTradable(okx, "PAXG", "USDT"));
        assertEquals(1, CLIENT.calls);

        CLOCK.advance(Duration.ofHours(2));
        assertFalse(catalog.isTradable(okx, "PAXG", "USDT"));
        assertTrue(catalog.isTradable(okx, "ETH", "USDC"));
        assertEquals(2, CLIENT.calls);
        assertEquals(2, repository.count());
    }

    @Test
    @DisplayName("Panne du provider : catalogue conservé (jamais vidé), pas de boucle d'appels, nouvelle tentative après 5 min")
    void failureKeepsCatalog() {
        catalog.isTradable(okx, "BTC", "USDC");
        CLOCK.advance(Duration.ofHours(25));
        CLIENT.failure = new InstrumentLookupException("OKX en panne");

        assertTrue(catalog.isTradable(okx, "PAXG", "USDT"));
        assertEquals(2, CLIENT.calls);
        assertEquals(4, repository.count());

        assertTrue(catalog.isTradable(okx, "BTC", "USDC"));
        assertTrue(catalog.isTradable(okx, "BTC", "USDC"));
        assertEquals(2, CLIENT.calls);

        CLOCK.advance(Duration.ofMinutes(6));
        assertTrue(catalog.isTradable(okx, "BTC", "USDC"));
        assertEquals(3, CLIENT.calls);

        CLIENT.failure = null;
        CLOCK.advance(Duration.ofMinutes(6));
        catalog.isTradable(okx, "BTC", "USDC");
        assertEquals(4, CLIENT.calls);
        CLOCK.advance(Duration.ofHours(1));
        catalog.isTradable(okx, "BTC", "USDC");
        assertEquals(4, CLIENT.calls);
    }

    @Test
    @DisplayName("Catalogue absent ET provider injoignable => InstrumentLookupException (pas de faux « absent »)")
    void noCatalogAndDown() {
        CLIENT.failure = new InstrumentLookupException("OKX en panne");

        assertThrows(InstrumentLookupException.class, () -> catalog.isTradable(okx, "BTC", "USDC"));
        assertThrows(InstrumentLookupException.class, () -> catalog.liveInstruments(okx));
        assertEquals(1, CLIENT.calls);
        assertEquals(0, repository.count());
    }

    @Test
    @DisplayName("Réponse vide : refresh explicite refusé, catalogue en base inchangé")
    void emptyResponseNeverWipes() {
        catalog.isTradable(okx, "BTC", "USDC");
        CLIENT.response = new ArrayList<>();

        assertThrows(InstrumentLookupException.class, () -> catalog.refresh(okx));
        assertEquals(4, repository.count());
        assertTrue(catalog.isTradable(okx, "BTC", "USDC"));
    }

    @Test
    @DisplayName("Refresh explicite : repasse par le client même si le catalogue est frais")
    void explicitRefresh() {
        catalog.isTradable(okx, "BTC", "USDC");
        catalog.refresh(okx);
        assertEquals(2, CLIENT.calls);
        assertEquals(4, repository.count());
    }
}
