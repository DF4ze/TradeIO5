package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveWalletQueryService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/rainbow-live/live-wallet} : lecture du dernier snapshot en base (OK / BLOCKED / UNAVAILABLE / STALE /
 * sans binding / sans snapshot), isolation entre users, absence de secret, aucune dépendance exchange.
 */
@DataJpaTest
@Import({RainbowLivePresetService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService.class, RainbowLiveWalletQueryService.class, RainbowLiveWalletControllerTest.ClockConfig.class})
@DisplayName("RainbowLiveWalletController : wallet réel affiché depuis le snapshot")
class RainbowLiveWalletControllerTest {

    private static final String URL = "/api/rainbow-live/live-wallet";
    private static final Instant FETCHED = Instant.parse("2026-10-09T23:55:03Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-10T00:05:00Z"));
        }
    }

    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveWalletQueryService queryService;
    @Autowired private RainbowLiveBindingRepository bindingRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private jakarta.persistence.EntityManager em;

    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        as(alice);
        mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveWalletController(queryService, facade))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    private void as(User u) {
        when(facade.getConnectedUser()).thenReturn(u);
    }

    private RainbowLivePreset preset(User user, String asset, String name) {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor(asset);
        return presetService.create(user, new CreateRequest(asset, name, true, 6, 1000, c.toTuning(), c.toGlobals()));
    }

    private RainbowLiveBinding bind(User user, String asset, RainbowLivePreset preset, double bag, int priority) {
        Wallet w = walletRepository.save(Wallet.builder().name("OKX " + user.getUsername() + " " + asset).source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).user(user).build());
        return bindingRepository.save(RainbowLiveBinding.builder().user(user).assetSymbol(asset).preset(preset)
                .wallet(w).bagPercent(bag).priority(priority).build());
    }

    private RainbowLivePassBlock live(PortfolioStatus status, LiveBlockReason reason, RainbowLiveAction action,
                                      Double amount, Double quantity) {
        return RainbowLivePassBlock.builder().liveStatus(status).liveWalletId(1L).liveFetchedAt(FETCHED)
                .liveCashUsdc(500.0).livePositionQty(0.02).liveTradableQty(0.01).liveCashReserved(50.0)
                .liveBlockReason(reason).liveActionType(action).liveActionAmountUsdc(amount)
                .liveActionQuantity(quantity).actionType(RainbowLiveAction.BUY).actionAmountUsdc(999.0).build();
    }

    private void run(User user, RainbowLivePreset preset, LocalDate day, RainbowLivePassBlock b2355, RainbowLivePassBlock b0005) {
        runRepository.save(RainbowLiveRun.builder().preset(preset).user(user).assetSymbol(preset.getAssetSymbol())
                .day(day).pass2355(b2355).pass0005(b0005).build());
    }

    @Test
    @DisplayName("OK : wallet, statut, fetchedAt, cash, position, bag, cash réservé et action recommandée")
    void ok() throws Exception {
        RainbowLivePreset p = preset(alice, "BTC", "live");
        bind(alice, "BTC", p, 60.0, 0);
        run(alice, p, LocalDate.of(2026, 10, 9),
                live(PortfolioStatus.OK, LiveBlockReason.NONE, RainbowLiveAction.BUY, 100.0, 0.001), null);
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].assetSymbol").value("BTC"))
                .andExpect(jsonPath("$[0].presetName").value("live"))
                .andExpect(jsonPath("$[0].exchange").value("OKX"))
                .andExpect(jsonPath("$[0].bagPercent").value(60.0))
                .andExpect(jsonPath("$[0].snapshot.status").value("OK"))
                .andExpect(jsonPath("$[0].snapshot.pass").value("T2355"))
                .andExpect(jsonPath("$[0].snapshot.fetchedAt").exists())
                .andExpect(jsonPath("$[0].snapshot.cashUsdc").value(500.0))
                .andExpect(jsonPath("$[0].snapshot.positionQty").value(0.02))
                .andExpect(jsonPath("$[0].snapshot.tradableQty").value(0.01))
                .andExpect(jsonPath("$[0].snapshot.cashReserved").value(50.0))
                .andExpect(jsonPath("$[0].snapshot.blockReason").value("NONE"))
                .andExpect(jsonPath("$[0].snapshot.liveActionType").value("BUY"))
                .andExpect(jsonPath("$[0].snapshot.liveActionAmountUsdc").value(100.0));
    }

    @Test
    @DisplayName("BLOCKED : action BLOCKED + INSUFFICIENT_CASH restitués (l'action mock n'est pas exposée comme live)")
    void blocked() throws Exception {
        RainbowLivePreset p = preset(alice, "ETH", "live");
        bind(alice, "ETH", p, 100.0, 1);
        run(alice, p, LocalDate.of(2026, 10, 9),
                live(PortfolioStatus.OK, LiveBlockReason.INSUFFICIENT_CASH, RainbowLiveAction.BLOCKED, null, null), null);
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshot.liveActionType").value("BLOCKED"))
                .andExpect(jsonPath("$[0].snapshot.blockReason").value("INSUFFICIENT_CASH"))
                .andExpect(jsonPath("$[0].snapshot.liveActionAmountUsdc").doesNotExist());
    }

    @Test
    @DisplayName("UNAVAILABLE et STALE : statuts restitués, action NONE")
    void unavailableAndStale() throws Exception {
        RainbowLivePreset btc = preset(alice, "BTC", "live");
        RainbowLivePreset eth = preset(alice, "ETH", "live");
        bind(alice, "BTC", btc, 100.0, 0);
        bind(alice, "ETH", eth, 100.0, 1);
        run(alice, btc, LocalDate.of(2026, 10, 9),
                live(PortfolioStatus.UNAVAILABLE, LiveBlockReason.UNAVAILABLE, RainbowLiveAction.NONE, null, null), null);
        run(alice, eth, LocalDate.of(2026, 10, 9),
                live(PortfolioStatus.STALE, LiveBlockReason.UNAVAILABLE, RainbowLiveAction.NONE, null, null), null);
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshot.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[0].snapshot.liveActionType").value("NONE"))
                .andExpect(jsonPath("$[1].snapshot.status").value("STALE"));
    }

    @Test
    @DisplayName("Dernier run, bloc 00:05 prioritaire sur 23:55 ; run sans snapshot live => snapshot nul")
    void latestRunAndBlockSelection() throws Exception {
        RainbowLivePreset p = preset(alice, "BTC", "live");
        bind(alice, "BTC", p, 100.0, 0);
        run(alice, p, LocalDate.of(2026, 10, 8),
                live(PortfolioStatus.UNAVAILABLE, LiveBlockReason.UNAVAILABLE, RainbowLiveAction.NONE, null, null), null);
        run(alice, p, LocalDate.of(2026, 10, 9),
                live(PortfolioStatus.STALE, LiveBlockReason.UNAVAILABLE, RainbowLiveAction.NONE, null, null),
                live(PortfolioStatus.OK, LiveBlockReason.NONE, RainbowLiveAction.SELL, 10.0, 0.0001));
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshot.day[2]").value(9))
                .andExpect(jsonPath("$[0].snapshot.day[1]").value(10))
                .andExpect(jsonPath("$[0].snapshot.pass").value("T0005"))
                .andExpect(jsonPath("$[0].snapshot.status").value("OK"));

        RainbowLivePreset fresh = preset(alice, "PAXG", "fresh");
        bind(alice, "PAXG", fresh, 100.0, 2);
        run(alice, fresh, LocalDate.of(2026, 10, 9), RainbowLivePassBlock.builder().close(1.0).build(), null);
        mvc.perform(get(URL)).andExpect(jsonPath("$[1].assetSymbol").value("PAXG"))
                .andExpect(jsonPath("$[1].snapshot").doesNotExist());
    }

    @Test
    @DisplayName("Sans binding : liste vide (page inchangée)")
    void noBinding() throws Exception {
        preset(alice, "BTC", "sim");
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Isolation : un user ne voit que ses bindings et snapshots")
    void isolation() throws Exception {
        RainbowLivePreset ap = preset(alice, "BTC", "live");
        bind(alice, "BTC", ap, 100.0, 0);
        run(alice, ap, LocalDate.of(2026, 10, 9),
                live(PortfolioStatus.OK, LiveBlockReason.NONE, RainbowLiveAction.NONE, null, null), null);
        as(bob);
        mvc.perform(get(URL)).andExpect(jsonPath("$.length()").value(0));
        RainbowLivePreset bp = preset(bob, "BTC", "live-bob");
        bind(bob, "BTC", bp, 10.0, 0);
        mvc.perform(get(URL)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].presetName").value("live-bob"))
                .andExpect(jsonPath("$[0].bagPercent").value(10.0))
                .andExpect(jsonPath("$[0].snapshot").doesNotExist());
    }

    @Test
    @DisplayName("Aucun secret ni credential dans la réponse")
    void noSecretInResponse() throws Exception {
        RainbowLivePreset p = preset(alice, "BTC", "live");
        bind(alice, "BTC", p, 100.0, 0);
        run(alice, p, LocalDate.of(2026, 10, 9),
                live(PortfolioStatus.OK, LiveBlockReason.NONE, RainbowLiveAction.NONE, null, null), null);
        String body = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.toLowerCase().contains("credential") || body.toLowerCase().contains("secret")
                || body.toLowerCase().contains("passphrase") || body.toLowerCase().contains("apikey"));
    }

    @Test
    @DisplayName("Affichage sans exchange : le service ne dépend que des repositories (ni lecteur de soldes, ni BindingCheck)")
    void serviceHasNoExchangeDependency() {
        for (Constructor<?> c : RainbowLiveWalletQueryService.class.getDeclaredConstructors()) {
            for (Class<?> t : c.getParameterTypes()) {
                assertTrue(t.getSimpleName().endsWith("Repository"), "dépendance inattendue : " + t.getName());
            }
        }
        for (Field f : RainbowLiveWalletQueryService.class.getDeclaredFields()) {
            assertTrue(f.getType().getSimpleName().endsWith("Repository"), "champ inattendu : " + f.getType().getName());
        }
        assertEquals(2, List.of(RainbowLiveWalletQueryService.class.getDeclaredFields()).size());
    }
}
