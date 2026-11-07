package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
@Import({AssetAffectationService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertFixtures.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService.class, RainbowLiveBindingService.class, RainbowLiveBindingControllerTest.TestConfig.class})
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

    @Autowired private fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertFixtures fixtures;
    @Autowired private RainbowLiveBindingService bindingService;
    @Autowired private AssetAffectationService affectationService;
    @Autowired private fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService basePresetService;
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
        basePresetService.ensureBasePresets();
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

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body)
            throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private Map<String, Object> createBody(String asset, Long walletId, Double bag) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("assetSymbol", asset);
        m.put("walletId", walletId);
        m.put("bagPercent", bag);
        return m;
    }

    @Test
    @DisplayName("Création : défauts bag 100 % et priorité par actif (BTC=0, ETH=1, PAXG=2), check retourné")
    void createWithDefaults() throws Exception {
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.binding.bagPercent").value(100.0))
                .andExpect(jsonPath("$.binding.priority").value(0))
                .andExpect(jsonPath("$.binding.exchange").value("OKX"))
                .andExpect(jsonPath("$.check.status").value("OK"))
                .andExpect(jsonPath("$.check.ok").value(true));
        json(post("/api/rainbow-live/bindings"), createBody("PAXG", aliceWallet.getId(), 40.0))
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
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.check.status").value("NOT_TRADABLE_WITHOUT_FIAT"))
                .andExpect(jsonPath("$.check.blocked").value(true))
                .andExpect(jsonPath("$.binding.tradability").value("NOT_TRADABLE_WITHOUT_FIAT"))
                .andExpect(jsonPath("$.check.ok").value(false));
        assertEquals(1, bindingRepository.count());
    }

    @Test
    @DisplayName("Unicité (user, actif, wallet) : en base et via l'API (409) ; même actif sur un autre wallet accepté")
    void uniqueUserAsset() throws Exception {
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), null))
                .andExpect(status().isCreated());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), null))
                .andExpect(status().isConflict());

        // Même actif sur un autre wallet (autre exchange) : autorisé.
        Wallet aliceKraken = wallet(alice, "Kraken");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceKraken.getId(), null))
                .andExpect(status().isCreated());
        Wallet aliceBinance = wallet(alice, "Binance");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceBinance.getId(), null))
                .andExpect(status().isCreated());

        bindingRepository.saveAndFlush(RainbowLiveBinding.builder().user(bob).assetSymbol("BTC")
                .affectation(affectationService.forAsset(bob, "BTC")).wallet(bobWallet).bagPercent(100).priority(0).build());
        RainbowLiveBinding dup = RainbowLiveBinding.builder().user(bob).assetSymbol("BTC")
                .affectation(affectationService.forAsset(bob, "BTC")).wallet(bobWallet).bagPercent(100).priority(0).build();
        assertThrows(DataIntegrityViolationException.class, () -> bindingRepository.saveAndFlush(dup));
    }

    @Test
    @DisplayName("Même Affectation sur deux wallets : 201 + 201 ; changement de wallet vers un couple déjà lié : 409")
    void sameAffectationTwoWallets() throws Exception {
        Wallet kraken = wallet(alice, "Kraken");
        Wallet binance = wallet(alice, "Binance");
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), null))
                .andExpect(status().isCreated());
        Long krakenBinding = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", kraken.getId(), null, null)).binding().getId();
        assertEquals(2, bindingRepository.findAllByAffectation(affectationService.forAsset(alice, "BTC")).size());
        json(put("/api/rainbow-live/bindings/" + krakenBinding), Map.of("walletId", aliceWallet.getId()))
                .andExpect(status().isConflict());
        json(put("/api/rainbow-live/bindings/" + krakenBinding), Map.of("walletId", binance.getId()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("baseAmount du lien : nul par défaut, posé/remis à nul, refusé si <= 0")
    void linkBaseAmount() {
        RainbowLiveBinding created = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", aliceWallet.getId(), null, null)).binding();
        assertNull(created.getBaseAmount());
        Long id = created.getId();
        assertEquals(25.0, bindingService.update(alice, id, new RainbowLiveBindingService.UpdateRequest(null, null, null, 25.0,
                false, null, false)).binding().getBaseAmount());
        assertEquals(25.0, bindingService.update(alice, id, new RainbowLiveBindingService.UpdateRequest(null, 50.0, null))
                .binding().getBaseAmount());
        assertThrows(IllegalArgumentException.class, () -> bindingService.update(alice, id,
                new RainbowLiveBindingService.UpdateRequest(null, null, null, 0.0, false, null, false)));
        assertNull(bindingService.update(alice, id, new RainbowLiveBindingService.UpdateRequest(null, null, null, null, true, null, false))
                .binding().getBaseAmount());
    }

    @Test
    @DisplayName("Isolation : wallet ou binding d'un autre user => 404")
    void isolation() throws Exception {
        json(post("/api/rainbow-live/bindings"), createBody("BTC", bobWallet.getId(), null))
                .andExpect(status().isNotFound());

        Long id = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", aliceWallet.getId(), null, null)).binding().getId();
        as(bob);
        mvc.perform(get("/api/rainbow-live/bindings/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/rainbow-live/bindings/" + id + "/check")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/rainbow-live/bindings/" + id)).andExpect(status().isNotFound());
        json(put("/api/rainbow-live/bindings/" + id), Map.of("bagPercent", 10.0)).andExpect(status().isNotFound());
        mvc.perform(get("/api/rainbow-live/bindings")).andExpect(jsonPath("$.length()").value(0));
        assertTrue(bindingRepository.findById(id).isPresent());
    }

    @Test
    @DisplayName("Validations : bagPercent hors bornes, actif inconnu, wallet absent => 400")
    void validations() throws Exception {
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), 100.1))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), -1.0))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("SOL", aliceWallet.getId(), null))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", null, null))
                .andExpect(status().isBadRequest());
        json(post("/api/rainbow-live/bindings"), createBody("BTC", aliceWallet.getId(), 0.0))
                .andExpect(status().isCreated());
        assertEquals(1, bindingRepository.count());
    }

    @Test
    @DisplayName("Retrait du binding : archivé (ligne conservée, exécution coupée), le preset reste, relier le réactive")
    void deleteBinding() throws Exception {
        Long id = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", aliceWallet.getId(), null, null)).binding().getId();
        bindingService.setExecution(alice, id, true);
        mvc.perform(delete("/api/rainbow-live/bindings/" + id)).andExpect(status().isNoContent());
        assertEquals(1, bindingRepository.count());
        RainbowLiveBinding archived = bindingRepository.findById(id).orElseThrow();
        assertTrue(archived.isArchived());
        assertFalse(archived.isExecutionEnabled());
        assertTrue(bindingService.list(alice).isEmpty());
        mvc.perform(delete("/api/rainbow-live/bindings/" + id)).andExpect(status().isNotFound());

        Long again = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", aliceWallet.getId(), null, null)).binding().getId();
        assertEquals(id, again);
        assertEquals(1, bindingRepository.count());
        assertFalse(bindingRepository.findById(id).orElseThrow().isArchived());
        assertEquals(1, bindingService.list(alice).size());
    }

    @Test
    @DisplayName("GET /{id}/check relance la vérification")
    void checkEndpoint() throws Exception {
        Long id = bindingService.create(alice, new RainbowLiveBindingService.CreateRequest("BTC", aliceWallet.getId(), null, null)).binding().getId();
        when(CHECK.check(any(RainbowLiveBinding.class)))
                .thenReturn(new BindingCheckResult(BindingCheckStatus.CREDENTIAL_INVALID, "clé rejetée"));
        mvc.perform(get("/api/rainbow-live/bindings/" + id + "/check")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREDENTIAL_INVALID"))
                .andExpect(jsonPath("$.ok").value(false));
    }
}
