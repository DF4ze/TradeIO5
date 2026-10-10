package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot.OrderBookLevel;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheck;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Endpoint {@code GET /bindings/{id}/path-quote} : services de binding réels sur H2, exchange/catalogue mockés. */
@DataJpaTest
@Import({RainbowLivePresetService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver.class,
        fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService.class, RainbowLiveBindingService.class,
        RainbowLiveBindingPathQuoteTest.TestConfig.class})
@DisplayName("GET /bindings/{id}/path-quote : devis du chemin, propriétaire seulement")
class RainbowLiveBindingPathQuoteTest {

    static final BindingCheck CHECK = mock(BindingCheck.class);

    @TestConfiguration
    static class TestConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-10T08:00:00Z"));
        }

        @Bean
        BindingCheck bindingCheck() {
            return CHECK;
        }
    }

    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveBindingService bindingService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private ApiCredentialRepository credentialRepository;

    private final ReadOnlyBalanceReader reader = mock(ReadOnlyBalanceReader.class);
    private final TradingFeeProvider fees = mock(TradingFeeProvider.class);
    private final OrderBookClient books = mock(OrderBookClient.class);
    private final InstrumentCatalog catalog = mock(InstrumentCatalog.class);
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;
    private User alice;
    private User bob;
    private Long aliceBindingId;

    @BeforeEach
    void setUp() {
        when(CHECK.check(any(fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding.class)))
                .thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "OK"));
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        WebProvider okx = providerRepository.save(WebProvider.builder().code(WebProviderCode.OKX).name("OKX")
                .apiBaseUrl("https://okx.test").build());
        ApiCredential credential = credentialRepository.save(ApiCredential.builder().user(alice).webProvider(okx)
                .apiKey("k").secretKey("s").passphrase("p").enabled(true).createdAt(LocalDateTime.now()).build());
        Wallet wallet = walletRepository.save(Wallet.builder().name("OKX").source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).webProvider(okx).credential(credential).user(alice).enabled(true).build());
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("PAXG");
        RainbowLivePreset preset = presetService.create(alice, new CreateRequest("PAXG", "live", true, 6, 1000, c.toTuning(), c.toGlobals()));
        aliceBindingId = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("PAXG", preset.getId(),
                wallet.getId(), null, null)).binding().getId();

        when(reader.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(fees.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(books.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(catalog.supports(WebProviderCode.OKX)).thenReturn(true);
        when(groups.members(AssetGroup.USD)).thenReturn(List.of("USDC", "USDT"));
        when(reader.getAvailableBalances(any())).thenReturn(Map.of("USDT", new BigDecimal("1000")));
        when(catalog.liveInstruments(any())).thenReturn(List.of(new InstrumentInfo("PAXG-USDT", "PAXG", "USDT",
                new BigDecimal("0.0001"), new BigDecimal("0.0001"), new BigDecimal("0.1"))));
        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(new BigDecimal("0.05"), new BigDecimal("0.05")));
        when(books.fetchBook(any(), anyString(), anyInt())).thenReturn(new BookResult(new OrderBookSnapshot(
                List.of(new OrderBookLevel(new BigDecimal("99.9"), new BigDecimal("1000"))),
                List.of(new OrderBookLevel(new BigDecimal("100.1"), new BigDecimal("1000")))), PathEstimation.BOOK));

        BindingPathQuoteService quoteService = new BindingPathQuoteService(bindingService, List.of(reader), List.of(fees),
                List.of(books), catalog, groups, new PathFinder(new FeeTest()), new FeeTest(), "");
        as(alice);
        mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveBindingController(bindingService, quoteService, facade))
                .setControllerAdvice(new RainbowLiveControllerAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
    }

    private void as(User u) {
        when(facade.getConnectedUser()).thenReturn(u);
    }

    @Test
    @DisplayName("Propriétaire : devis OK, jambe PAXG-USDT, coût en %, niveau Fee Test, pas d'avertissement")
    void owner() throws Exception {
        mvc.perform(get("/api/rainbow-live/bindings/" + aliceBindingId + "/path-quote").param("amount", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.source").value("USDT"))
                .andExpect(jsonPath("$.target").value("PAXG"))
                .andExpect(jsonPath("$.legs.length()").value(1))
                .andExpect(jsonPath("$.legs[0].instId").value("PAXG-USDT"))
                .andExpect(jsonPath("$.legs[0].side").value("BUY"))
                .andExpect(jsonPath("$.totalCostPct").value(0.15))
                .andExpect(jsonPath("$.feeTestLevel").value("GREEN"))
                .andExpect(jsonPath("$.estimation").value("BOOK"))
                .andExpect(jsonPath("$.warning").doesNotExist());
    }

    @Test
    @DisplayName("Binding d'un autre utilisateur ou inexistant => 404, aucun appel exchange")
    void otherUserOrUnknown() throws Exception {
        as(bob);
        mvc.perform(get("/api/rainbow-live/bindings/" + aliceBindingId + "/path-quote").param("amount", "1000"))
                .andExpect(status().isNotFound());
        as(alice);
        mvc.perform(get("/api/rainbow-live/bindings/999999/path-quote").param("amount", "1000"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verify(fees, org.mockito.Mockito.never()).getFeeRate(any(), anyString());
        org.mockito.Mockito.verify(books, org.mockito.Mockito.never()).fetchBook(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Montant absent, nul ou négatif => 400")
    void invalidAmount() throws Exception {
        mvc.perform(get("/api/rainbow-live/bindings/" + aliceBindingId + "/path-quote")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/rainbow-live/bindings/" + aliceBindingId + "/path-quote").param("amount", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/rainbow-live/bindings/" + aliceBindingId + "/path-quote").param("amount", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Clé rejetée => 409 avec message sans secret ; exchange en panne => 503")
    void exchangeFailures() throws Exception {
        when(reader.getAvailableBalances(any())).thenThrow(new CredentialRejectedException("OKX : erreur API code=50113 msg=Invalid Sign"));
        mvc.perform(get("/api/rainbow-live/bindings/" + aliceBindingId + "/path-quote").param("amount", "1000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OKX : erreur API code=50113 msg=Invalid Sign"));

        org.mockito.Mockito.doReturn(Map.of("USDT", new BigDecimal("1000"))).when(reader).getAvailableBalances(any());
        when(books.fetchBook(any(), anyString(), anyInt()))
                .thenThrow(new fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException("carnet et ticker indisponibles"));
        mvc.perform(get("/api/rainbow-live/bindings/" + aliceBindingId + "/path-quote").param("amount", "1000"))
                .andExpect(status().isServiceUnavailable());
    }
}
