package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.ExchangeInstrumentRepository;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BindingCheck branché sur le vrai catalogue en base : mêmes statuts qu'avant, plus d'appel réseau quand il est frais. */
@DataJpaTest
@DisplayName("BindingCheck sur InstrumentCatalog (cache DB)")
class BindingCheckCatalogTest {

    private static class CountingClient implements InstrumentListClient {
        int calls;
        List<Listed> response;
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

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ExchangeInstrumentRepository repository;

    private final CountingClient client = new CountingClient();
    private final ReadOnlyBalanceReader reader = mock(ReadOnlyBalanceReader.class);
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private final WebProvider okx = WebProvider.builder().code(WebProviderCode.OKX).apiBaseUrl("https://okx").build();
    private BindingCheck check;
    private Wallet wallet;

    private static InstrumentListClient.Listed pair(String base, String quote) {
        return new InstrumentListClient.Listed(base + "-" + quote, base, quote, true, new BigDecimal("0.0001"),
                new BigDecimal("0.0001"), new BigDecimal("0.1"));
    }

    @BeforeEach
    void setUp() {
        client.response = List.of(pair("BTC", "USDC"), pair("PAXG", "USDT"), pair("USDC", "USDT"));
        InstrumentCatalog catalog = new InstrumentCatalog(repository, List.of(client),
                new FixedDomainClock(Instant.parse("2026-10-10T08:00:00Z")), transactionManager, Duration.ofHours(24));
        when(reader.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(reader.getAvailableBalances(any())).thenReturn(Map.of());
        when(groups.members(AssetGroup.USD)).thenReturn(List.of("USDC", "USDT"));
        check = new BindingCheck(List.of(reader), catalog, groups);
        ApiCredential credential = ApiCredential.builder().id(1L).webProvider(okx).apiKey("k").secretKey("s")
                .passphrase("p").enabled(true).build();
        wallet = Wallet.builder().id(7L).name("OKX").enabled(true).webProviderCode(WebProviderCode.OKX)
                .credential(credential).build();
    }

    @Test
    @DisplayName("Mêmes statuts qu'avant : OK, TRADABLE_VIA_BRIDGE, NOT_TRADABLE_WITHOUT_FIAT")
    void sameStatuses() {
        assertEquals(BindingCheckStatus.OK, check.check(wallet, "BTC").status());
        BindingCheckResult paxg = check.check(wallet, "PAXG");
        assertEquals(BindingCheckStatus.TRADABLE_VIA_BRIDGE, paxg.status());
        assertEquals("USDT", paxg.quoteMember());
        assertEquals("USDC/USDT", paxg.bridgePair());
        assertEquals(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, check.check(wallet, "ETH").status());
    }

    @Test
    @DisplayName("Catalogue frais : une seule lecture réseau pour tous les check()")
    void noNetworkWhenFresh() {
        check.check(wallet, "BTC");
        check.check(wallet, "PAXG");
        check.check(wallet, "ETH");
        check.check(wallet, "BTC");

        assertEquals(1, client.calls);
    }

    @Test
    @DisplayName("Catalogue absent et provider injoignable => INSTRUMENT_UNAVAILABLE")
    void catalogAbsentAndDown() {
        client.failure = new InstrumentLookupException("OKX en panne");

        assertEquals(BindingCheckStatus.INSTRUMENT_UNAVAILABLE, check.check(wallet, "BTC").status());
    }
}
