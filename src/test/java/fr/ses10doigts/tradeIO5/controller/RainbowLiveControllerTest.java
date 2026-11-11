package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveQueryService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveRunService;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Services réels sur H2 ({@code @DataJpaTest}) + MockMvc standalone (le contrôle d'accès est testé dans
 * {@link RainbowLiveControllerSecurityTest}) ; l'utilisateur connecté est piloté via un faux
 * {@link IAuthenticationFacade}.
 */
@DataJpaTest
@Import({AssetAffectationService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertFixtures.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService.class, RainbowLiveRunService.class,
        RainbowLiveQueryService.class,
        RainbowLiveControllerTest.ClockConfig.class})
@DisplayName("RainbowLiveController : API REST des Affectations")
class RainbowLiveControllerTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate DAY3 = LocalDate.of(2026, 10, 3);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-03T23:55:00Z"));
        }
    }

    @Autowired private fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertFixtures fixtures;
    @Autowired private AssetAffectationService affectationService;
    @Autowired private fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService basePresetService;
    @Autowired private RainbowLiveRunService runService;
    @Autowired private RainbowLiveQueryService queryService;
    @Autowired private AssetAffectationRepository affectationRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        basePresetService.ensureBasePresets();
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        as(alice);
        mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveController(queryService, facade))
                .setControllerAdvice(new RainbowLiveControllerAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
    }

    private void as(User u) {
        when(facade.getConnectedUser()).thenReturn(u);
    }

    private AssetAffectation btc(User u) {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        return fixtures.affectation(u, "BTC", "Bench", c.toTuning(), c.toGlobals());
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private static RainbowLivePassBlock block(RainbowLiveAction type, Double amount, Double qty, double close) {
        return RainbowLivePassBlock.builder().close(close).sma(close).atr(1.0).zone(2).buyArmed(true)
                .actionType(type).actionAmountUsdc(amount).actionQuantity(qty).actionPrice(close).build();
    }

    private void play(AssetAffectation p, LocalDate day, RainbowLivePassBlock b) {
        runService.upsert(p, day, b);
    }

    // ---------------------------------------------------------------- defaults / liste / seed

    @Test
    @DisplayName("GET /defaults : actifs, devise du cash, valeurs par défaut, config par actif (sans champ parasite)")
    void defaults() throws Exception {
        mvc.perform(get("/api/rainbow-live/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets.length()").value(3))
                .andExpect(jsonPath("$.cashCurrency").value("USD"))
                .andExpect(jsonPath("$.analysisWindowMonths").value(6))
                .andExpect(jsonPath("$.baseAmount").value(1.0))
                .andExpect(jsonPath("$.configs.BTC.tuning.smaPeriod").value(50))
                .andExpect(jsonPath("$.configs.BTC.tuning.buyReentryMode").value("TRAILING_STOP"))
                .andExpect(jsonPath("$.configs.ETH.globals.moonOn").value(true))
                .andExpect(jsonPath("$.configs.BTC.tuning.valid").doesNotExist())
                .andExpect(jsonPath("$.reentryModes.length()").value(3))
                .andExpect(jsonPath("$.reentryModes[0]").value("TRAILING_STOP"))
                .andExpect(jsonPath("$.zones.length()").value(6))
                .andExpect(jsonPath("$.zones[0].code").value(0))
                .andExpect(jsonPath("$.zones[0].name").value("EXTREME_BAS"))
                .andExpect(jsonPath("$.zones[5].name").value("EXTREME_HAUT"))
                .andExpect(jsonPath("$.zones[2].label").value("Zone ×1"));
    }

    @Test
    @DisplayName("GET /affectations : une Affectation par actif sur les presets de base (3, inactives) ; filtre ?asset ; actif invalide ⇒ 400")
    void listCreatesBaseAffectationsAndFilters() throws Exception {
        mvc.perform(get("/api/rainbow-live/affectations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].assetSymbol").value("BTC"))
                .andExpect(jsonPath("$[0].complete").value(true))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].trendPreset.origin").value("SYSTEM"))
                .andExpect(jsonPath("$[0].rainbowUp.origin").value("SYSTEM"))
                .andExpect(jsonPath("$[0].rainbowDown.origin").value("SYSTEM"))
                .andExpect(jsonPath("$[0].runCount").value(0))
                .andExpect(jsonPath("$[0].lastRun").doesNotExist());

        mvc.perform(get("/api/rainbow-live/affectations").param("asset", "ETH"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].assetSymbol").value("ETH"));
        mvc.perform(get("/api/rainbow-live/affectations").param("asset", "SOL")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Aucun endpoint de création / suppression / activation d'Affectation")
    void noMutationEndpoints() throws Exception {
        mvc.perform(get("/api/rainbow-live/affectations")).andExpect(status().isOk());
        Long id = affectationRepository.findByUserAndAssetSymbolAndSystemTrue(alice, "BTC").orElseThrow().getId();
        mvc.perform(post("/api/rainbow-live/affectations")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/rainbow-live/affectations/" + id)).andExpect(status().is4xxClientError());
        mvc.perform(patch("/api/rainbow-live/affectations/" + id + "/enabled")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("GET /affectations : nb de runs, 1er run et dernier run résumé")
    void listEnriched() throws Exception {
        AssetAffectation p = btc(alice);
        play(p, DAY1, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        play(p, DAY2, block(RainbowLiveAction.NONE, null, null, 110_000));
        em.flush();

        mvc.perform(get("/api/rainbow-live/affectations").param("asset", "BTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].system").value(true)).andExpect(jsonPath("$[0].runCount").value(0))
                .andExpect(jsonPath("$[1].runCount").value(2))
                .andExpect(jsonPath("$[1].firstRunDay").value("2026-10-01"))
                .andExpect(jsonPath("$[1].lastRun.day").value("2026-10-02"))
                .andExpect(jsonPath("$[1].lastRun.actionType").value("NONE"))
                .andExpect(jsonPath("$[1].lastRun.close").value(110_000.0));
    }

    @Test
    @DisplayName("Affectation d'un autre user ou inexistante ⇒ 404 (pas de fuite d'existence)")
    void notOwnedOrMissing() throws Exception {
        AssetAffectation aliceAffectation = btc(alice);
        as(bob);

        for (Long id : new Long[]{aliceAffectation.getId(), 987654L}) {
            String base = "/api/rainbow-live/affectations/" + id;
            mvc.perform(get(base)).andExpect(status().isNotFound());
        }
    }
}
