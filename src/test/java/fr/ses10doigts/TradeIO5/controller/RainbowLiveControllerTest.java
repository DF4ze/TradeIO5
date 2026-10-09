package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetTemplateService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.UpdateRequest;
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
@Import({RainbowLivePresetService.class, RainbowLivePresetTemplateService.class, RainbowLiveRunService.class,
        RainbowLiveQueryService.class,
        RainbowLiveControllerTest.ClockConfig.class})
@DisplayName("RainbowLiveController : API REST du bench grandeur nature")
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

    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLivePresetTemplateService templateService;
    @Autowired private RainbowLiveRunService runService;
    @Autowired private RainbowLiveQueryService queryService;
    @Autowired private RainbowLivePresetRepository presetRepository;
    @Autowired private RainbowLiveMockWalletRepository walletRepository;
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
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        as(alice);
        mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveController(presetService, queryService, facade))
                .setControllerAdvice(new RainbowLiveControllerAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
    }

    private void as(User u) {
        when(facade.getConnectedUser()).thenReturn(u);
    }

    private static CreateRequest createRequest(String asset, String name) {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor(asset);
        return new CreateRequest(asset, name, true, 6, 1000, c.toTuning(), c.toGlobals());
    }

    private RainbowLivePreset btcPreset(User u, String name) {
        return presetService.create(u, createRequest("BTC", name));
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

    private void play(RainbowLivePreset p, LocalDate day, RainbowLivePass pass, RainbowLivePassBlock b) {
        runService.upsertPass(p, day, pass, b);
    }

    // ---------------------------------------------------------------- defaults / liste / seed

    @Test
    @DisplayName("GET /defaults : actifs, stablecoin, valeurs par défaut, config par actif (sans champ parasite)")
    void defaults() throws Exception {
        mvc.perform(get("/api/rainbow-live/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets.length()").value(3))
                .andExpect(jsonPath("$.stablecoin").value("USDC"))
                .andExpect(jsonPath("$.analysisWindowMonths").value(6))
                .andExpect(jsonPath("$.initialCapitalUsdc").value(1000.0))
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
                .andExpect(jsonPath("$.zones[2].label").value("Zone ×1"))
                .andExpect(jsonPath("$.systemPrefix").value("[Système] "));
    }

    @Test
    @DisplayName("GET /presets : copie des templates système (6, inactifs, préfixés) ; filtre ?asset ; actif invalide ⇒ 400")
    void listCopiesSystemPresetsAndFilters() throws Exception {
        templateService.ensureTemplates();
        mvc.perform(get("/api/rainbow-live/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].assetSymbol").value("BTC"))
                .andExpect(jsonPath("$[0].name").value("[Système] Bench global"))
                .andExpect(jsonPath("$[0].system").value(true))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[1].name").value("[Système] Trend Mix"))
                .andExpect(jsonPath("$[1].mode").value("TREND_MIX"))
                .andExpect(jsonPath("$[0].wallet.cashUsdc").value(1000.0))
                .andExpect(jsonPath("$[0].wallet.equityUsdc").doesNotExist())
                .andExpect(jsonPath("$[0].runCount").value(0))
                .andExpect(jsonPath("$[0].lastRun").doesNotExist());

        mvc.perform(get("/api/rainbow-live/presets")).andExpect(jsonPath("$.length()").value(6));
        mvc.perform(get("/api/rainbow-live/presets").param("asset", "ETH"))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].assetSymbol").value("ETH"));
        mvc.perform(get("/api/rainbow-live/presets").param("asset", "SOL")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Preset système : PUT et DELETE ⇒ 409, PATCH /enabled autorisé ; nom préfixé refusé à la création")
    void systemPresetIsLocked() throws Exception {
        templateService.ensureTemplates();
        mvc.perform(get("/api/rainbow-live/presets")).andExpect(status().isOk());
        Long id = presetRepository.findByUserAndAssetSymbolAndName(alice, "BTC", "[Système] Bench global").orElseThrow().getId();
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");

        putJson("/api/rainbow-live/presets/" + id, new UpdateRequest("x", true, 6, c.toTuning(), c.toGlobals()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
        mvc.perform(delete("/api/rainbow-live/presets/" + id)).andExpect(status().isConflict());

        mvc.perform(patch("/api/rainbow-live/presets/" + id + "/enabled").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.name").value("[Système] Bench global"));

        postJson("/api/rainbow-live/presets", createRequest("BTC", "[Système] perso")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /templates : 6 templates en lecture seule (aucun endpoint d'écriture)")
    void templatesReadOnly() throws Exception {
        templateService.ensureTemplates();
        mvc.perform(get("/api/rainbow-live/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].assetSymbol").value("BTC"))
                .andExpect(jsonPath("$[0].name").value("Bench global"))
                .andExpect(jsonPath("$[0].mode").value("FIXED"))
                .andExpect(jsonPath("$[0].trendConfig").doesNotExist())
                .andExpect(jsonPath("$[1].name").value("Trend Mix"))
                .andExpect(jsonPath("$[1].trendConfig.trend.shortWindow").exists());
        mvc.perform(post("/api/rainbow-live/templates")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/rainbow-live/templates/1")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("GET /presets : wallet, nb de runs, 1er run et dernier run résumé")
    void listEnriched() throws Exception {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        play(p, DAY1, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        play(p, DAY2, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 110_000));
        em.flush();

        mvc.perform(get("/api/rainbow-live/presets").param("asset", "BTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runCount").value(2))
                .andExpect(jsonPath("$[0].firstRunDay").value("2026-10-01"))
                .andExpect(jsonPath("$[0].lastRun.day").value("2026-10-02"))
                .andExpect(jsonPath("$[0].lastRun.pass").value("T2355"))
                .andExpect(jsonPath("$[0].lastRun.actionType").value("NONE"))
                .andExpect(jsonPath("$[0].wallet.cashUsdc").value(900.0))
                .andExpect(jsonPath("$[0].wallet.positionQuantity").value(0.001))
                .andExpect(jsonPath("$[0].wallet.lastClose").value(110_000.0))
                .andExpect(jsonPath("$[0].wallet.equityUsdc").value(900.0 + 0.001 * 110_000));
    }

    // ---------------------------------------------------------------- CRUD

    @Test
    @DisplayName("CRUD complet : POST 201, GET détail, PUT (actif et capital inchangés), DELETE 204 puis 404")
    void crud() throws Exception {
        postJson("/api/rainbow-live/presets", createRequest("ETH", "Mon ETH"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetSymbol").value("ETH"))
                .andExpect(jsonPath("$.name").value("Mon ETH"))
                .andExpect(jsonPath("$.initialCapitalUsdc").value(1000.0))
                .andExpect(jsonPath("$.tuning.smaPeriod").value(36))
                .andExpect(jsonPath("$.globals.baseAmount").value(1.0));
        Long id = presetRepository.findByUserAndAssetSymbolAndName(alice, "ETH", "Mon ETH").orElseThrow().getId();

        mvc.perform(get("/api/rainbow-live/presets/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("ETH");
        putJson("/api/rainbow-live/presets/" + id, new UpdateRequest("Renommé", false, 12,
                c.toTuning().toBuilder().set("smaPeriod", 20).build(), c.toGlobals()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renommé"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.analysisWindowMonths").value(12))
                .andExpect(jsonPath("$.tuning.smaPeriod").value(20))
                .andExpect(jsonPath("$.assetSymbol").value("ETH"))
                .andExpect(jsonPath("$.initialCapitalUsdc").value(1000.0));

        mvc.perform(delete("/api/rainbow-live/presets/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/rainbow-live/presets/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Preset d'un autre user ou inexistant ⇒ 404 sur tous les endpoints (pas de fuite d'existence)")
    void notOwnedOrMissing() throws Exception {
        RainbowLivePreset alicePreset = btcPreset(alice, "Bench");
        as(bob);
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        UpdateRequest upd = new UpdateRequest("x", true, 6, c.toTuning(), c.toGlobals());

        for (Long id : new Long[]{alicePreset.getId(), 987654L}) {
            String base = "/api/rainbow-live/presets/" + id;
            mvc.perform(get(base)).andExpect(status().isNotFound());
            putJson(base, upd).andExpect(status().isNotFound());
            mvc.perform(delete(base)).andExpect(status().isNotFound());
            mvc.perform(get(base + "/runs")).andExpect(status().isNotFound());
            mvc.perform(get(base + "/performance")).andExpect(status().isNotFound());
            mvc.perform(get(base + "/delta")).andExpect(status().isNotFound());
        }
        assertTrue(presetRepository.findById(alicePreset.getId()).isPresent(), "le preset d'alice est intact");
    }

    @Test
    @DisplayName("Payload invalide ⇒ 400 (tuning invalide, actif interdit, fenêtre 0, capital 0, JSON illisible) ; nom en double ⇒ 409")
    void validation() throws Exception {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        RainbowAtrTuning badTuning = c.toTuning().toBuilder().set("atrMultUp1", 9.0).build(); // up1 > up2
        postJson("/api/rainbow-live/presets", new CreateRequest("BTC", "a", true, 6, 1000, badTuning, c.toGlobals()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        postJson("/api/rainbow-live/presets", new CreateRequest("SOL", "a", true, 6, 1000, c.toTuning(), c.toGlobals()))
                .andExpect(status().isBadRequest());
        postJson("/api/rainbow-live/presets", new CreateRequest("BTC", "a", true, 0, 1000, c.toTuning(), c.toGlobals()))
                .andExpect(status().isBadRequest());
        postJson("/api/rainbow-live/presets", new CreateRequest("BTC", "a", true, 6, 0, c.toTuning(), c.toGlobals()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rainbow-live/presets").contentType(MediaType.APPLICATION_JSON).content("{pas du json"))
                .andExpect(status().isBadRequest());
        assertTrue(presetService.list(alice).isEmpty(), "aucun preset créé par les payloads invalides");

        postJson("/api/rainbow-live/presets", createRequest("BTC", "doublon")).andExpect(status().isCreated());
        postJson("/api/rainbow-live/presets", createRequest("BTC", "doublon")).andExpect(status().isConflict());

        Long id = presetRepository.findByUserAndAssetSymbolAndName(alice, "BTC", "doublon").orElseThrow().getId();
        putJson("/api/rainbow-live/presets/" + id, new UpdateRequest("x", true, 0, c.toTuning(), c.toGlobals()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE ⇒ runs et wallet mock disparus")
    void deleteRemovesRunsAndWallet() throws Exception {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        play(p, DAY1, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        em.flush();
        assertEquals(1, runRepository.count());

        mvc.perform(delete("/api/rainbow-live/presets/" + p.getId())).andExpect(status().isNoContent());
        em.clear();

        assertEquals(0, runRepository.count());
        assertEquals(0, walletRepository.count());
    }

    // ---------------------------------------------------------------- runs / config modifiée

    @Test
    @DisplayName("Édition ⇒ historique intact, run suivant avec nouvelle config : configChanged + changedParams ; from/to garde le calcul sur l'historique complet")
    void editThenNextRunMarksConfigChange() throws Exception {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        play(p, DAY1, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 100_000));
        play(p, DAY2, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 101_000));

        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        putJson("/api/rainbow-live/presets/" + p.getId(), new UpdateRequest("Bench", true, 6,
                c.toTuning().toBuilder().set("smaPeriod", 36).build(),
                c.toGlobals().toBuilder().set("athRefDdBuyPct", 45.0).build())).andExpect(status().isOk());
        play(p, DAY3, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 102_000));
        em.flush();

        String runs = "/api/rainbow-live/presets/" + p.getId() + "/runs";
        mvc.perform(get(runs))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].day").value("2026-10-01"))
                .andExpect(jsonPath("$[0].configChanged").value(false))
                .andExpect(jsonPath("$[0].changedParams.length()").value(0))
                .andExpect(jsonPath("$[1].configChanged").value(false))
                .andExpect(jsonPath("$[0].pass0005").doesNotExist())
                .andExpect(jsonPath("$[0].pass2355.close").value(100_000.0))
                .andExpect(jsonPath("$[2].configChanged").value(true))
                .andExpect(jsonPath("$[2].changedParams[0]").value("smaPeriod"))
                .andExpect(jsonPath("$[2].changedParams[1]").value("globals.athRefDdBuyPct"))
                .andExpect(jsonPath("$[2].changedParams.length()").value(2));
        // l'historique des jours passés garde l'ancienne config (snapshot immuable)
        assertEquals(50, runRepository.findByPresetAndDay(p, DAY1).orElseThrow().getConfig().getSmaPeriod());

        // filtre from : le 1er run de la plage reste comparé au run précédent hors plage
        mvc.perform(get(runs).param("from", "2026-10-03"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].configChanged").value(true))
                .andExpect(jsonPath("$[0].changedParams.length()").value(2));
        mvc.perform(get(runs).param("from", "2026-10-02").param("to", "2026-10-02"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].configChanged").value(false));
        mvc.perform(get(runs).param("from", "pas-une-date")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/rainbow-live/presets/" + p.getId() + "/performance"))
                .andExpect(jsonPath("$.configMarkers.length()").value(1))
                .andExpect(jsonPath("$.configMarkers[0].day").value("2026-10-03"))
                .andExpect(jsonPath("$.configMarkers[0].changedParams[0]").value("smaPeriod"));
    }

    // ---------------------------------------------------------------- performance

    @Test
    @DisplayName("GET /performance sans run ⇒ 200 vide, métriques à 0, JSON sans NaN/Infinity")
    void performanceEmpty() throws Exception {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        String body = mvc.perform(get("/api/rainbow-live/presets/" + p.getId() + "/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(0))
                .andExpect(jsonPath("$.series.length()").value(0))
                .andExpect(jsonPath("$.markers.length()").value(0))
                .andExpect(jsonPath("$.metrics.invested").value(0.0))
                .andExpect(jsonPath("$.metrics.pnlPercent").value(0.0))
                .andExpect(jsonPath("$.wallet.equityUsdc").value(1000.0))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("NaN") || body.contains("Infinity"));
    }

    @Test
    @DisplayName("GET /performance : métriques, séries, marqueurs ; ?to borne la plage ; JSON sans NaN")
    void performance() throws Exception {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        play(p, DAY1, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 1.0, 100));
        play(p, DAY2, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 150));
        play(p, DAY3, RainbowLivePass.T2355, block(RainbowLiveAction.SELL, 120.0, 0.5, 240));
        em.flush();
        String url = "/api/rainbow-live/presets/" + p.getId() + "/performance";

        String body = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(3))
                .andExpect(jsonPath("$.metrics.invested").value(100.0))
                .andExpect(jsonPath("$.metrics.saleProceeds").value(120.0))
                .andExpect(jsonPath("$.metrics.realizedGain").value(70.0))     // 120 − 100 × 0,5
                .andExpect(jsonPath("$.metrics.currentValue").value(120.0))    // 0,5 × 240
                .andExpect(jsonPath("$.metrics.totalGain").value(140.0))
                .andExpect(jsonPath("$.metrics.pnlPercent").value(140.0))
                .andExpect(jsonPath("$.metrics.fixedInvested").value(3.0))     // baseAmount 1 × 3 jours
                .andExpect(jsonPath("$.series.length()").value(3))
                .andExpect(jsonPath("$.markers.length()").value(2))
                .andExpect(jsonPath("$.markers[0].type").value("BUY"))
                .andExpect(jsonPath("$.configMarkers.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("NaN") || body.contains("Infinity"));

        mvc.perform(get(url).param("to", "2026-10-02"))
                .andExpect(jsonPath("$.days").value(2))
                .andExpect(jsonPath("$.metrics.saleProceeds").value(0.0))
                .andExpect(jsonPath("$.metrics.currentValue").value(150.0));
    }

    // ---------------------------------------------------------------- delta

    @Test
    @DisplayName("GET /delta : jours à 2 blocs seulement, action différente détaillée, écarts")
    void delta() throws Exception {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        play(p, DAY1, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 10.0, 0.1, 100));
        play(p, DAY1, RainbowLivePass.T0005, block(RainbowLiveAction.NONE, null, null, 101));
        play(p, DAY2, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 110));   // sans 00:05
        play(p, DAY3, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 120));
        play(p, DAY3, RainbowLivePass.T0005, block(RainbowLiveAction.NONE, null, null, 120));
        em.flush();

        mvc.perform(get("/api/rainbow-live/presets/" + p.getId() + "/delta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysCompared").value(2))
                .andExpect(jsonPath("$.daysIgnored").value(1))
                .andExpect(jsonPath("$.daysActionDiffers").value(1))
                .andExpect(jsonPath("$.actionDifferences[0].day").value("2026-10-01"))
                .andExpect(jsonPath("$.actionDifferences[0].pass2355.type").value("BUY"))
                .andExpect(jsonPath("$.actionDifferences[0].pass0005.type").value("NONE"))
                .andExpect(jsonPath("$.indicators.close.maxAbs").value(1.0))
                .andExpect(jsonPath("$.indicators.close.meanAbs").value(0.5));

        mvc.perform(get("/api/rainbow-live/presets/" + p.getId() + "/delta").param("from", "2026-10-03"))
                .andExpect(jsonPath("$.daysCompared").value(1))
                .andExpect(jsonPath("$.daysActionDiffers").value(0));
    }
}
