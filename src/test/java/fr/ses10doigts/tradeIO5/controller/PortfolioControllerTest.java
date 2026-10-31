package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.portfolio.PortfolioQueryService;
import fr.ses10doigts.tradeIO5.service.portfolio.PortfolioSnapshotService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Patrimoine, performance par actif et achats/ventes d'un lien : lecture base seule, isolation entre utilisateurs. */
@DataJpaTest
@Import({RainbowLivePresetService.class, RainbowLivePresetConfigResolver.class, RainbowLivePresetEventService.class,
        PortfolioQueryService.class, PortfolioSnapshotService.class, PortfolioControllerTest.ClockConfig.class})
@DisplayName("PortfolioController : patrimoine, PnL, achats/ventes")
class PortfolioControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final Instant AT = Instant.parse("2026-10-20T23:55:00Z");
    private static final ModulationSettings BAND = new ModulationSettings(40, 80, 1, 0);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(AT);
        }
    }

    @Autowired private PortfolioQueryService queryService;
    @Autowired private PortfolioSnapshotService snapshotService;
    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveBindingRepository bindingRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private UserRepository userRepository;

    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private MockMvc mvc;
    private User alice;
    private User bob;
    private Wallet wallet;
    private RainbowLiveBinding binding;
    private WebProvider okx;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        okx = providerRepository.save(WebProvider.builder().code(WebProviderCode.OKX).name("OKX").apiBaseUrl("https://okx.test").build());
        wallet = walletRepository.save(Wallet.builder().name("OKX").source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).webProvider(okx).user(alice).build());
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        RainbowLivePreset preset = presetService.create(alice, new CreateRequest("BTC", "live", true, 6, c.toTuning(), c.toGlobals()));
        binding = bindingRepository.save(RainbowLiveBinding.builder().user(alice).assetSymbol("BTC").preset(preset)
                .wallet(wallet).bagPercent(100).priority(0).build());
        when(facade.getConnectedUser()).thenReturn(alice);
        mvc = MockMvcBuilders.standaloneSetup(new PortfolioController(queryService, mock(fr.ses10doigts.tradeIO5.service.portfolio.HomeOverviewService.class),
                mock(fr.ses10doigts.tradeIO5.service.portfolio.ExchangeTradeImportService.class), facade))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    private void snapshot(LocalDate day, double cash, double btcQty, double btcPrice) {
        snapshotService.record(alice, wallet, day, new PortfolioReading(cash, Map.of("BTC", btcQty), AT, PortfolioStatus.OK, wallet.getId()),
                List.of("BTC"), Map.of("BTC", btcPrice), BAND);
    }

    private void trade(TradeSide side, String qty, String price, int minute) {
        transactionRepository.save(Transaction.builder().externalTransactionId("t" + minute).user(alice).webProvider(okx)
                .asset("BTC").quantity(new BigDecimal(qty)).price(new BigDecimal(price)).side(side).wallet(wallet)
                .timestamp(LocalDateTime.of(2026, 10, 10, 12, minute)).build());
    }

    @Test
    @DisplayName("Résumé : série, courant, PnL de période hors flux, autonomie ; 'all' couvre tout l'historique")
    void summary() throws Exception {
        snapshot(TODAY.minusDays(1), 600.0, 0.01, 40000.0);
        snapshot(TODAY, 200.0, 0.015, 60000.0);

        mvc.perform(get("/api/portfolio/summary").param("period", "7d")).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(2))
                .andExpect(jsonPath("$.current.total").value(1100.0))
                .andExpect(jsonPath("$.current.exposurePct").value(900.0 / 1100.0 * 100.0))
                .andExpect(jsonPath("$.pnl.amount").value(100.0))
                .andExpect(jsonPath("$.pnl.pct").value(10.0))
                .andExpect(jsonPath("$.flowsIncluded").value(false))
                .andExpect(jsonPath("$.autonomyDays").isNumber());
        mvc.perform(get("/api/portfolio/summary").param("period", "all")).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(2));
    }

    @Test
    @DisplayName("Sans instantané : série vide, courant et PnL nuls (« — »), pas de 0")
    void summaryWithoutSnapshot() throws Exception {
        mvc.perform(get("/api/portfolio/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.length()").value(0))
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.pnl").doesNotExist())
                .andExpect(jsonPath("$.autonomyDays").doesNotExist());
    }

    @Test
    @DisplayName("Période inconnue : 400")
    void badPeriod() throws Exception {
        mvc.perform(get("/api/portfolio/summary").param("period", "2w")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Actifs : valeur, investi net (achats − ventes), PnL et prix moyen d'achat")
    void assets() throws Exception {
        snapshot(TODAY, 200.0, 0.015, 60000.0);
        trade(TradeSide.BUY, "0.01", "40000", 1);
        trade(TradeSide.BUY, "0.01", "50000", 2);
        trade(TradeSide.SELL, "0.005", "60000", 3);

        mvc.perform(get("/api/portfolio/assets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].assetSymbol").value("BTC"))
                .andExpect(jsonPath("$[0].value").value(900.0))
                .andExpect(jsonPath("$[0].investedNet").value(600.0))
                .andExpect(jsonPath("$[0].pnl").value(300.0))
                .andExpect(jsonPath("$[0].pnlPct").value(50.0))
                .andExpect(jsonPath("$[0].avgBuyPrice").value(45000.0));
    }

    @Test
    @DisplayName("Achats/ventes d'un lien : liste et prix moyen ; lien d'autrui ou inconnu : 404")
    void tradesAndIsolation() throws Exception {
        trade(TradeSide.BUY, "0.01", "40000", 1);
        trade(TradeSide.SELL, "0.005", "60000", 2);
        String url = "/api/rainbow-live/bindings/" + binding.getId() + "/transactions";

        mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath("$.trades.length()").value(2))
                .andExpect(jsonPath("$.trades[0].side").value("BUY"))
                .andExpect(jsonPath("$.avgBuyPrice").value(40000.0));

        when(facade.getConnectedUser()).thenReturn(bob);
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get("/api/rainbow-live/bindings/999999/transactions")).andExpect(status().isNotFound());
        mvc.perform(get("/api/portfolio/assets")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
