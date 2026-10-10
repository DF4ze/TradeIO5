package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheck;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Services réels sur H2 + MockMvc standalone ; {@link BindingCheck} est mocké (testé dans BindingCheckTest). */
@DataJpaTest
@Import({RainbowLivePresetService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService.class, RainbowLiveBindingService.class, RainbowLiveBindingControllerTest.TestConfig.class})
@DisplayName("RainbowLiveBindingController : bindings preset live + wallet")
class RainbowLiveBindingControllerTest {

    static final BindingCheck CHECK = mock(BindingCheck.class);

    @TestConfiguration
    static class TestConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-09T23:55:00Z"));
        }

        @Bean
        BindingCheck bindingCheck() {
            return CHECK;
        }
    }

    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveBindingService bindingService;
    @Autowired private RainbowLiveBindingRepository bindingRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private MockMvc mvc;
    private User alice;
    private User bob;
    private Wallet aliceWallet;
    private Wallet bobWallet;

    @BeforeEach
    void setUp() {
        when(CHECK.check(any(RainbowLiveBinding.class)))
                .thenReturn(new BindingCheckResult(BindingCheckStatus.OK, "OK"));
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        aliceWallet = wallet(alice, "OKX");
        bobWallet = wallet(bob, "OKX");
        as(alice);
        mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveBindingController(bindingService, mock(fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService.class), facade))
                .setControllerAdvice(new RainbowLiveControllerAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
    }

    private void as(User u) {
        when(facade.getConnectedUser()).thenReturn(u);
    }

    private Wallet wallet(User user, String name) {
        return walletRepository.save(Wallet.builder().name(name).source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).user(user).build());
    }

    private RainbowLivePreset preset(User user, String asset, String name) {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor(asset);
        return presetService.create(user, new CreateRequest(asset, name, true, 6, 1000, c.toTuning(), c.toGlobals()));
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body)
            throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private Map<String, Object> createBody(String asset, Long presetId, Long walletId, Double bag) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("assetSymbol", asset);
        m.put("presetId", presetId);
        m.put("walletId", walletId);
        m.put("bagPercent", bag);
        return m;
    }

    @Test
    @DisplayName("Création : défauts bag 100 % et priorité par actif (BTC=0, ETH=1, PAXG=2), check retourné")
    void createWithDefaults() throws Exception {
        RainbowLivePreset btc = preset(alice, "BTC", "live-btc");
        RainbowLivePreset paxg = preset(alice, "PAXG", "live-paxg");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", btc.getId(), aliceWallet.getId(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.binding.bagPercent").value(100.0))
                .andExpect(jsonPath("$.binding.priority").value(0))
                .andExpect(jsonPath("$.binding.exchange").value("OKX"))
                .andExpect(jsonPath("$.check.status").value("OK"))
                .andExpect(jsonPath("$.check.ok").value(true));
        json(post("/api/rainbow-live/bindings"), createBody("PAXG", paxg.getId(), aliceWallet.getId(), 40.0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.binding.priority").value(2))
                .andExpect(jsonPath("$.binding.bagPercent").value(40.0));
        mvc.perform(get("/api/rainbow-live/bindings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("Un check KO n'empêche pas l'enregistrement et est restitué")
    void koCheckStillSaved() throws Exception {
        when(CHECK.check(any(RainbowLiveBinding.class)))
                .thenReturn(new BindingCheckResult(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, "absent"));
        RainbowLivePreset btc = preset(alice, "BTC", "p");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", btc.getId(), aliceWallet.getId(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.check.status").value("NOT_TRADABLE_WITHOUT_FIAT"))
                .andExpect(jsonPath("$.check.blocked").value(true))
                .andExpect(jsonPath("$.binding.tradability").value("NOT_TRADABLE_WITHOUT_FIAT"))
                .andExpect(jsonPath("$.check.ok").value(false));
        assertEquals(1, bindingRepository.count());
    }

    @Test
    @DisplayName("Unicité (user, actif) : en base et via l'API (409)")
    void uniqueUserAsset() throws Exception {
        RainbowLivePreset p1 = preset(alice, "BTC", "p1");
        RainbowLivePreset p2 = preset(alice, "BTC", "p2");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", p1.getId(), aliceWallet.getId(), null))
                .andExpect(status().isCreated());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", p2.getId(), aliceWallet.getId(), null))
                .andExpect(status().isConflict());

        bindingRepository.saveAndFlush(RainbowLiveBinding.builder().user(bob).assetSymbol("BTC")
                .preset(preset(bob, "BTC", "b1")).wallet(bobWallet).bagPercent(100).priority(0).build());
        RainbowLiveBinding dup = RainbowLiveBinding.builder().user(bob).assetSymbol("BTC")
                .preset(preset(bob, "BTC", "b2")).wallet(bobWallet).bagPercent(100).priority(0).build();
        assertThrows(DataIntegrityViolationException.class, () -> bindingRepository.saveAndFlush(dup));
    }

    @Test
    @DisplayName("Isolation : preset, wallet ou binding d'un autre user => 404")
    void isolation() throws Exception {
        RainbowLivePreset alicePreset = preset(alice, "BTC", "p");
        RainbowLivePreset bobPreset = preset(bob, "BTC", "p");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", bobPreset.getId(), aliceWallet.getId(), null))
                .andExpect(status().isNotFound());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", alicePreset.getId(), bobWallet.getId(), null))
                .andExpect(status().isNotFound());

        Long id = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC",
                alicePreset.getId(), aliceWallet.getId(), null, null)).binding().getId();
        as(bob);
        mvc.perform(get("/api/rainbow-live/bindings/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/rainbow-live/bindings/" + id + "/check")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/rainbow-live/bindings/" + id)).andExpect(status().isNotFound());
        json(put("/api/rainbow-live/bindings/" + id), Map.of("bagPercent", 10.0)).andExpect(status().isNotFound());
        mvc.perform(get("/api/rainbow-live/bindings")).andExpect(jsonPath("$.length()").value(0));
        assertTrue(bindingRepository.findById(id).isPresent());
    }

    @Test
    @DisplayName("Validations : mauvais actif du preset, bagPercent hors bornes, actif inconnu => 400")
    void validations() throws Exception {
        RainbowLivePreset eth = preset(alice, "ETH", "p");
        RainbowLivePreset btc = preset(alice, "BTC", "p");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", eth.getId(), aliceWallet.getId(), null))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", btc.getId(), aliceWallet.getId(), 100.1))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", btc.getId(), aliceWallet.getId(), -1.0))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("SOL", btc.getId(), aliceWallet.getId(), null))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", null, aliceWallet.getId(), null))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", btc.getId(), aliceWallet.getId(), 0.0))
                .andExpect(status().isCreated());
        assertEquals(1, bindingRepository.count());
    }

    @Test
    @DisplayName("Bascule du live : le binding pointe le nouveau preset, l'ancien est libre (supprimable) et conservé")
    void switchLivePreset() throws Exception {
        RainbowLivePreset p1 = preset(alice, "BTC", "p1");
        RainbowLivePreset p2 = preset(alice, "BTC", "p2");
        Long id = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", p1.getId(),
                aliceWallet.getId(), null, null)).binding().getId();

        assertThrows(IllegalArgumentException.class, () -> presetService.delete(alice, p1.getId()));

        json(put("/api/rainbow-live/bindings/" + id), Map.of("presetId", p2.getId(), "bagPercent", 55.0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.presetId").value(p2.getId()))
                .andExpect(jsonPath("$.binding.bagPercent").value(55.0));
        em.flush();
        em.clear();
        assertEquals(1, bindingRepository.count());
        assertFalse(bindingRepository.existsByPreset(presetService.get(alice, p1.getId())));
        assertTrue(bindingRepository.existsByPreset(presetService.get(alice, p2.getId())));
        presetService.delete(alice, p1.getId());
        assertThrows(IllegalArgumentException.class, () -> presetService.delete(alice, p2.getId()));
    }

    @Test
    @DisplayName("Suppression du binding : le preset reste")
    void deleteBinding() throws Exception {
        RainbowLivePreset p1 = preset(alice, "BTC", "p1");
        Long id = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", p1.getId(),
                aliceWallet.getId(), null, null)).binding().getId();
        mvc.perform(delete("/api/rainbow-live/bindings/" + id)).andExpect(status().isNoContent());
        assertEquals(0, bindingRepository.count());
        assertEquals(p1.getId(), presetService.get(alice, p1.getId()).getId());
    }

    @Test
    @DisplayName("GET /{id}/check relance la vérification")
    void checkEndpoint() throws Exception {
        RainbowLivePreset p1 = preset(alice, "BTC", "p1");
        Long id = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", p1.getId(),
                aliceWallet.getId(), null, null)).binding().getId();
        when(CHECK.check(any(RainbowLiveBinding.class)))
                .thenReturn(new BindingCheckResult(BindingCheckStatus.CREDENTIAL_INVALID, "clé rejetée"));
        mvc.perform(get("/api/rainbow-live/bindings/" + id + "/check")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREDENTIAL_INVALID"))
                .andExpect(jsonPath("$.ok").value(false));
    }
}
