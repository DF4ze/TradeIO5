package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionLog;
import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.user.DisplayMode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import fr.ses10doigts.tradeIO5.repository.admin.AdminActionLogRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveEngineStateRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetEventRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrState;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAffectationService;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssetService;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssets;
import fr.ses10doigts.tradeIO5.service.expert.ExpertPresetSupport;
import fr.ses10doigts.tradeIO5.service.expert.ExpertPresetValidator;
import fr.ses10doigts.tradeIO5.service.expert.RainbowPresetService;
import fr.ses10doigts.tradeIO5.service.expert.TrendPresetService;
import fr.ses10doigts.tradeIO5.service.expert.simulation.SimulationEligibility;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.Preferences;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * API {@code /api/expert/**} : services réels sur H2 + MockMvc standalone ; utilisateur, rôle ADMIN et mode d'affichage
 * pilotés par des faux ({@link IAuthenticationFacade}, {@link UserPreferencesService}).
 */
@DataJpaTest
@Import({AssetAffectationService.class, ExpertBasePresetService.class, RainbowLivePresetEventService.class, AdminActionLogService.class,
        ExpertPresetSupport.class, ExpertAssets.class, ExpertPresetValidator.class, TrendPresetService.class, RainbowPresetService.class,
        ExpertAffectationService.class, ExpertAssetService.class, SimulationEligibility.class, ExpertApiTest.ClockConfig.class})
@DisplayName("Espace Expert : API des presets et des Affectations")
class ExpertApiTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-11-07T10:00:00Z"));
        }
    }

    @Autowired private ExpertBasePresetService basePresetService;
    @Autowired private AssetAffectationService affectationService;
    @Autowired private AssetAffectationRepository affectationRepository;
    @Autowired private RainbowLiveEngineStateRepository stateRepository;
    @Autowired private RainbowLivePresetEventRepository eventRepository;
    @Autowired private AdminActionLogRepository auditRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private AssetProviderRepository assetProviderRepository;
    @Autowired private TrendPresetService trendService;
    @Autowired private RainbowPresetService rainbowService;
    @Autowired private ExpertAffectationService expertAffectationService;
    @Autowired private ExpertAssetService expertAssetService;
    @Autowired private EntityManager em;

    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private final UserPreferencesService preferences = mock(UserPreferencesService.class);
    private MockMvc mvc;
    private User alice;
    private User bob;
    private User root;

    @BeforeEach
    void setUp() {
        basePresetService.ensureBasePresets();
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        root = userRepository.save(User.builder().username("root").email("root@example.com").password("x").build());
        Asset btc = assetRepository.save(Asset.builder().symbol("BTC").name("Bitcoin").decimals(8).build());
        Asset eth = assetRepository.save(Asset.builder().symbol("ETH").name("Ethereum").decimals(8).build());
        provider(btc, MarketDataSource.BINANCE, 0, true);
        provider(btc, MarketDataSource.KRAKEN, 1, true);
        provider(btc, MarketDataSource.OKX, 2, false);
        provider(eth, MarketDataSource.BINANCE, 0, true);
        ExpertAccessGuard guard = new ExpertAccessGuard(facade, preferences);
        mvc = MockMvcBuilders.standaloneSetup(new ExpertAssetController(guard, expertAssetService),
                        new ExpertTrendPresetController(guard, trendService), new ExpertRainbowPresetController(guard, rainbowService),
                        new ExpertAffectationController(guard, expertAffectationService))
                .setControllerAdvice(new ExpertControllerAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
        as(alice, false, DisplayMode.EXPERT);
    }

    private void provider(Asset asset, MarketDataSource source, int priority, boolean enabled) {
        assetProviderRepository.save(AssetProvider.builder().asset(asset).source(source).providerSymbol(asset.getSymbol()).priority(priority)
                .enabled(enabled).build());
    }

    private void as(User u, boolean admin, DisplayMode mode) {
        when(facade.getConnectedUser()).thenReturn(u);
        when(facade.getAuthentication()).thenReturn(new UsernamePasswordAuthenticationToken(u.getUsername(), "x",
                AuthorityUtils.createAuthorityList(admin ? "ROLE_ADMIN" : "ROLE_USER")));
        when(preferences.get(any())).thenReturn(new Preferences(null, mode, null, Set.of(), null, List.of()));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        return json(mvc.perform(get(url)).andExpect(status().isOk()));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private long created(String url, Object body) throws Exception {
        return json(postJson(url, body).andExpect(status().isCreated())).at("/header/id").asLong();
    }

    private long systemTrendId(String asset) throws Exception {
        for (JsonNode p : getJson("/api/expert/trend-presets?asset=" + asset)) {
            if ("SYSTEM".equals(p.get("origin").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("pas de Trend système");
    }

    private long systemRainbowId(String asset, String baseRole) throws Exception {
        for (JsonNode p : getJson("/api/expert/rainbow-presets?asset=" + asset)) {
            if (baseRole.equals(p.path("baseRole").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("pas de Rainbow " + baseRole);
    }

    /** Corps de PUT Trend construit depuis le détail lu, avec {@code confirm} + 1. */
    private ObjectNode trendUpdate(long id, String newName) throws Exception {
        JsonNode d = getJson("/api/expert/trend-presets/" + id);
        ObjectNode body = om.createObjectNode();
        body.put("name", newName == null ? d.at("/header/name").asText() : newName);
        body.set("referenceProvider", d.at("/header/referenceProvider"));
        ObjectNode trend = (ObjectNode) d.get("trend").deepCopy();
        trend.put("confirm", trend.get("confirm").asInt() + 1);
        body.set("trend", trend);
        body.set("rangeMapping", d.get("rangeMapping"));
        body.put("revision", d.at("/header/revision").asInt());
        return body;
    }

    private ObjectNode rainbowUpdate(long id) throws Exception {
        JsonNode d = getJson("/api/expert/rainbow-presets/" + id);
        ObjectNode body = om.createObjectNode();
        body.set("name", d.at("/header/name"));
        body.set("referenceProvider", d.at("/header/referenceProvider"));
        body.set("analysisWindowMonths", d.get("analysisWindowMonths"));
        body.set("tuning", d.get("tuning").deepCopy());
        body.set("globals", d.get("globals").deepCopy());
        body.put("revision", d.at("/header/revision").asInt());
        return body;
    }

    @Autowired private fr.ses10doigts.tradeIO5.repository.WalletRepository walletRepository;
    @Autowired private fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository bindingRepository;

    /** Affectation utilisateur éditable de BTC (copie des presets de l'instance Système). */
    private AssetAffectation editable(User u) {
        return affectationRepository.findByUserAndAssetSymbolOrderByIdAsc(u, "BTC").stream().filter(x -> !x.isSystem()).findFirst().orElseGet(() -> {
            AssetAffectation base = affectationService.forAsset(u, "BTC");
            return affectationService.create(u, "BTC", "Perso", base.getTrendPreset().getId(), base.getRainbowUp().getId(), base.getRainbowDown().getId());
        });
    }

    private AssetAffectation withState(User u) {
        AssetAffectation a = affectationService.forAsset(u, "BTC");
        stateRepository.save(RainbowLiveEngineState.of(a, DAY, RainbowAtrState.initial()));
        em.flush();
        return a;
    }

    private List<AdminActionLog> presetAudit() {
        return auditRepository.findAllByOrderByIdDesc(PageRequest.of(0, 100)).getContent().stream()
                .filter(l -> l.getAction() == AdminAction.PRESET).sorted(java.util.Comparator.comparing(AdminActionLog::getId)).toList();
    }

    // ---------------------------------------------------------------- accès

    @Test
    @DisplayName("Mode non EXPERT : 403 EXPERT_MODE_REQUIRED sur tous les endpoints (ADMIN compris)")
    void notExpertMode() throws Exception {
        as(alice, false, DisplayMode.ADVANCED);
        mvc.perform(get("/api/expert/assets")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EXPERT_MODE_REQUIRED"));
        mvc.perform(get("/api/expert/trend-presets?asset=BTC")).andExpect(status().isForbidden());
        mvc.perform(get("/api/expert/affectations")).andExpect(status().isForbidden());
        as(root, true, DisplayMode.SIMPLE);
        mvc.perform(get("/api/expert/rainbow-presets?asset=BTC")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXPERT_MODE_REQUIRED"));
    }

    @Test
    @DisplayName("GET /assets : actifs des presets de base, providers actifs seuls, statut depuis l'Affectation, éligibilité")
    void assets() throws Exception {
        mvc.perform(get("/api/expert/assets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].symbol").value("BTC"))
                .andExpect(jsonPath("$[0].name").value("Bitcoin"))
                .andExpect(jsonPath("$[0].providers.length()").value(2))
                .andExpect(jsonPath("$[0].providers[0].provider").value("BINANCE"))
                .andExpect(jsonPath("$[0].simulable").value(true))
                .andExpect(jsonPath("$[0].exploitable").value(false))
                .andExpect(jsonPath("$[0].eligibility.requiredPoints").isNumber())
                .andExpect(jsonPath("$[0].eligibility.providers.length()").value(2))
                .andExpect(jsonPath("$[2].symbol").value("PAXG"))
                .andExpect(jsonPath("$[2].simulable").value(false));
        affectationService.forAsset(alice, "BTC");
        mvc.perform(get("/api/expert/assets")).andExpect(jsonPath("$[0].exploitable").value(true));
    }

    // ---------------------------------------------------------------- visibilité / droits

    @Test
    @DisplayName("Liste : SYSTEM + presets de l'appelant ; le preset USER d'un autre est invisible (liste et détail 404)")
    void visibility() throws Exception {
        long base = systemTrendId("BTC");
        as(bob, false, DisplayMode.EXPERT);
        long bobCopy = created("/api/expert/trend-presets/" + base + "/duplicate", java.util.Map.of());
        as(alice, false, DisplayMode.EXPERT);
        JsonNode list = getJson("/api/expert/trend-presets?asset=BTC");
        assertEquals(1, list.size());
        assertEquals("SYSTEM", list.get(0).get("origin").asText());
        assertEquals("TREND", list.get(0).get("baseRole").asText());
        assertFalse(list.get(0).get("editable").asBoolean());
        assertFalse(list.get(0).get("mine").asBoolean());
        mvc.perform(get("/api/expert/trend-presets/" + bobCopy)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRESET_NOT_FOUND"));
        putJson("/api/expert/trend-presets/" + bobCopy, trendUpdate(base, null)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/expert/trend-presets/" + bobCopy)).andExpect(status().isNotFound());
        mvc.perform(post("/api/expert/trend-presets/" + bobCopy + "/duplicate")).andExpect(status().isNotFound());
        mvc.perform(get("/api/expert/trend-presets?asset=DOGE")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USER qui modifie un preset SYSTEM : 403 PRESET_SYSTEM_READ_ONLY (invite à dupliquer), rien n'est écrit ; suppression SYSTEM 403 même pour l'ADMIN")
    void userCannotEditSystem() throws Exception {
        long base = systemTrendId("BTC");
        int revision = getJson("/api/expert/trend-presets/" + base).at("/header/revision").asInt();
        int audited = presetAudit().size();
        putJson("/api/expert/trend-presets/" + base, trendUpdate(base, null)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRESET_SYSTEM_READ_ONLY"));
        putJson("/api/expert/rainbow-presets/" + systemRainbowId("BTC", "RAINBOW_BULL"), rainbowUpdate(systemRainbowId("BTC", "RAINBOW_BULL")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PRESET_SYSTEM_READ_ONLY"));
        assertEquals(revision, getJson("/api/expert/trend-presets/" + base).at("/header/revision").asInt());
        assertEquals(audited, presetAudit().size(), "refus : rien n'est tracé");
        mvc.perform(delete("/api/expert/trend-presets/" + base)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRESET_SYSTEM_UNDELETABLE"));
        as(root, true, DisplayMode.EXPERT);
        mvc.perform(delete("/api/expert/trend-presets/" + base)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("save-as-system : USER 403 ; ADMIN crée un nouveau SYSTEM, l'original est inchangé, nom déjà pris refusé")
    void saveAsSystem() throws Exception {
        long bull = systemRainbowId("BTC", "RAINBOW_BULL");
        ObjectNode body = rainbowUpdate(bull);
        body.put("name", "Bull alt");
        postJson("/api/expert/rainbow-presets/" + bull + "/save-as-system", body).andExpect(status().isForbidden());
        as(root, true, DisplayMode.EXPERT);
        String before = getJson("/api/expert/rainbow-presets/" + bull).at("/header/name").asText();
        int revision = getJson("/api/expert/rainbow-presets/" + bull).at("/header/revision").asInt();
        JsonNode created = json(postJson("/api/expert/rainbow-presets/" + bull + "/save-as-system", body).andExpect(status().isCreated()));
        assertEquals("SYSTEM", created.at("/header/origin").asText());
        assertEquals("Bull alt", created.at("/header/name").asText());
        assertEquals(before, getJson("/api/expert/rainbow-presets/" + bull).at("/header/name").asText());
        assertEquals(revision, getJson("/api/expert/rainbow-presets/" + bull).at("/header/revision").asInt());
        postJson("/api/expert/rainbow-presets/" + bull + "/save-as-system", body).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- duplication / création

    @Test
    @DisplayName("Duplication : même actif (nom dérivé unique), autre actif, provider copié seulement s'il porte l'actif cible, jamais affecté")
    void duplicate() throws Exception {
        long bull = systemRainbowId("BTC", "RAINBOW_BULL");
        String bullName = getJson("/api/expert/rainbow-presets/" + bull).at("/header/name").asText();
        putJson("/api/expert/rainbow-presets/" + bull, rainbowUpdate(bull)).andExpect(status().isForbidden());
        as(root, true, DisplayMode.EXPERT);
        ObjectNode withProvider = rainbowUpdate(bull);
        withProvider.put("referenceProvider", "kraken");
        putJson("/api/expert/rainbow-presets/" + bull, withProvider).andExpect(status().isOk())
                .andExpect(jsonPath("$.header.referenceProvider").value("KRAKEN")).andExpect(jsonPath("$.header.revision").value(2));
        as(alice, false, DisplayMode.EXPERT);

        postJson("/api/expert/rainbow-presets/" + bull + "/duplicate", java.util.Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.header.origin").value("USER")).andExpect(jsonPath("$.header.mine").value(true))
                .andExpect(jsonPath("$.header.editable").value(true)).andExpect(jsonPath("$.header.baseRole").doesNotExist())
                .andExpect(jsonPath("$.header.name").value(bullName + " (copie)")).andExpect(jsonPath("$.header.referenceProvider").value("KRAKEN"))
                .andExpect(jsonPath("$.header.revision").value(1)).andExpect(jsonPath("$.header.usedBy.length()").value(0));
        postJson("/api/expert/rainbow-presets/" + bull + "/duplicate", java.util.Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.header.name").value(bullName + " (copie) 2"));
        postJson("/api/expert/rainbow-presets/" + bull + "/duplicate", java.util.Map.of("name", bullName + " (copie)"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.details.name").exists());
        // autre actif : ETH n'est pas porté par KRAKEN => provider vide
        postJson("/api/expert/rainbow-presets/" + bull + "/duplicate", java.util.Map.of("assetSymbol", "eth", "name", "Mon ETH"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.header.assetSymbol").value("ETH"))
                .andExpect(jsonPath("$.header.referenceProvider").doesNotExist()).andExpect(jsonPath("$.header.name").value("Mon ETH"));
        postJson("/api/expert/rainbow-presets/" + bull + "/duplicate", java.util.Map.of("assetSymbol", "DOGE")).andExpect(status().isBadRequest());
        AssetAffectation a = affectationService.forAsset(alice, "BTC");
        assertEquals("SYSTEM", a.getRainbowUp().getOrigin().name(), "la duplication n'affecte jamais d'elle-même");
        assertEquals(1, getJson("/api/expert/rainbow-presets?asset=ETH").findValuesAsText("name").stream().filter("Mon ETH"::equals).count());
    }

    @Test
    @DisplayName("Création : copie du preset de base de l'actif (ou de copyFromId), nom obligatoire, provider validé contre l'actif")
    void create() throws Exception {
        postJson("/api/expert/trend-presets", java.util.Map.of("assetSymbol", "BTC", "name", "Mon trend")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.header.name").value("Mon trend")).andExpect(jsonPath("$.header.origin").value("USER"))
                .andExpect(jsonPath("$.trend.confirm").value(getJson("/api/expert/trend-presets/" + systemTrendId("BTC")).at("/trend/confirm").asInt()));
        postJson("/api/expert/trend-presets", java.util.Map.of("assetSymbol", "BTC", "name", "Mon trend")).andExpect(status().isBadRequest());
        postJson("/api/expert/trend-presets", java.util.Map.of("assetSymbol", "BTC")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists());
        postJson("/api/expert/trend-presets", java.util.Map.of("assetSymbol", "ETH", "name", "x", "referenceProvider", "KRAKEN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.referenceProvider").exists());
        postJson("/api/expert/rainbow-presets", java.util.Map.of("assetSymbol", "ETH", "name", "Eth perso", "referenceProvider", "binance"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.header.referenceProvider").value("BINANCE"))
                .andExpect(jsonPath("$.tuning.smaPeriod").value(getJson("/api/expert/rainbow-presets/" + systemRainbowId("ETH", "RAINBOW_BEAR")).at("/tuning/smaPeriod").asInt()));
    }

    // ---------------------------------------------------------------- modification

    @Test
    @DisplayName("Modification d'un preset USER installé : révision +1, états moteur purgés, événement ; autre Affectation intacte ; révision périmée ⇒ 409")
    void userUpdateResetsEngine() throws Exception {
        long copy = created("/api/expert/trend-presets/" + systemTrendId("BTC") + "/duplicate", java.util.Map.of());
        AssetAffectation mine = editable(alice);
        affectationService.update(alice, mine.getId(), null, copy, mine.getRainbowUp().getId(), mine.getRainbowDown().getId());
        stateRepository.save(RainbowLiveEngineState.of(mine, DAY, RainbowAtrState.initial()));
        AssetAffectation other = withState(bob);
        em.flush();
        long events = eventRepository.count();
        int audited = presetAudit().size();

        ObjectNode body = trendUpdate(copy, null);
        putJson("/api/expert/trend-presets/" + copy, body).andExpect(status().isOk()).andExpect(jsonPath("$.header.revision").value(2))
                .andExpect(jsonPath("$.header.usedBy[0].role").value("TREND")).andExpect(jsonPath("$.trend.confirm").value(body.at("/trend/confirm").asInt()));
        assertTrue(stateRepository.findAll().stream().noneMatch(s -> s.getAffectation().getId().equals(mine.getId())), "états de l'Affectation purgés");
        assertTrue(stateRepository.findAll().stream().anyMatch(s -> s.getAffectation().getId().equals(other.getId())), "autre utilisateur intact");
        assertEquals(events + 1, eventRepository.count());
        assertEquals(audited, presetAudit().size(), "preset USER : pas de ligne d'audit admin");

        putJson("/api/expert/trend-presets/" + copy, body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRESET_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.details.currentRevision").value(2));
        ObjectNode noRevision = trendUpdate(copy, null);
        noRevision.remove("revision");
        putJson("/api/expert/trend-presets/" + copy, noRevision).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Renommer sans changer les réglages : révision +1 mais ni reset moteur ni événement ; modification sans effet : rien")
    void renameDoesNotReset() throws Exception {
        long copy = created("/api/expert/rainbow-presets", java.util.Map.of("assetSymbol", "BTC", "name", "Perso"));
        AssetAffectation mine = editable(alice);
        affectationService.update(alice, mine.getId(), null, mine.getTrendPreset().getId(), copy, copy);
        stateRepository.save(RainbowLiveEngineState.of(mine, DAY, RainbowAtrState.initial()));
        em.flush();
        long events = eventRepository.count();
        ObjectNode body = rainbowUpdate(copy);
        body.put("name", "Perso renomme");
        putJson("/api/expert/rainbow-presets/" + copy, body).andExpect(status().isOk()).andExpect(jsonPath("$.header.revision").value(2))
                .andExpect(jsonPath("$.header.usedBy.length()").value(2));
        assertEquals(1, stateRepository.count());
        assertEquals(events, eventRepository.count());
        ObjectNode same = rainbowUpdate(copy);
        putJson("/api/expert/rainbow-presets/" + copy, same).andExpect(status().isOk()).andExpect(jsonPath("$.header.revision").value(2));
    }

    @Test
    @DisplayName("Impact d'un preset SYSTEM : ADMIN seul, comptes sans identité ; usedBy porte enabled")
    void systemPresetImpact() throws Exception {
        long base = systemTrendId("BTC");
        AssetAffectation a = withState(alice);
        withState(bob);
        fr.ses10doigts.tradeIO5.model.entity.currency.Wallet w = walletRepository.save(fr.ses10doigts.tradeIO5.model.entity.currency.Wallet.builder()
                .name("w-alice").source(fr.ses10doigts.tradeIO5.model.enumerate.WalletSource.EXCHANGE)
                .webProviderCode(fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode.OKX).user(alice).build());
        bindingRepository.save(fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding.builder().user(alice)
                .assetSymbol(a.getAssetSymbol()).affectation(a).wallet(w).bagPercent(100.0).priority(1).build());
        em.flush();
        as(alice, false, DisplayMode.EXPERT);
        mvc.perform(get("/api/expert/trend-presets/" + base + "/impact")).andExpect(status().isForbidden());
        for (JsonNode p : getJson("/api/expert/trend-presets?asset=BTC")) {
            if (p.get("id").asLong() == base) {
                assertTrue(p.at("/usedBy/0/enabled").asBoolean());
            }
        }
        as(root, true, DisplayMode.EXPERT);
        JsonNode impact = getJson("/api/expert/trend-presets/" + base + "/impact");
        assertEquals(2, impact.get("affectations").asInt());
        assertEquals(1, impact.get("activeEngines").asInt());
        assertEquals(2, impact.size(), "comptes seulement, aucune identité");
        long bull = systemRainbowId("BTC", "RAINBOW_BULL");
        assertEquals(2, getJson("/api/expert/rainbow-presets/" + bull + "/impact").get("affectations").asInt());
    }

    @Test
    @DisplayName("ADMIN modifie un preset SYSTEM : propagé à toutes les Affectations qui l'utilisent, états purgés, événement par utilisateur, audit PRESET avec le décompte")
    void adminUpdatesSystem() throws Exception {
        long base = systemTrendId("BTC");
        AssetAffectation a = withState(alice);
        AssetAffectation b = withState(bob);
        long events = eventRepository.count();
        int audited = presetAudit().size();
        as(root, true, DisplayMode.EXPERT);
        ObjectNode body = trendUpdate(base, null);
        putJson("/api/expert/trend-presets/" + base, body).andExpect(status().isOk()).andExpect(jsonPath("$.header.revision").value(2))
                .andExpect(jsonPath("$.header.editable").value(true)).andExpect(jsonPath("$.trend.confirm").value(body.at("/trend/confirm").asInt()));
        assertEquals(0, stateRepository.count(), "états des deux Affectations purgés");
        assertEquals(events + 2, eventRepository.count());
        assertEquals(body.at("/trend/confirm").asInt(), affectationRepository.findById(a.getId()).orElseThrow().getTrendPreset().toParams().confirm());
        assertEquals(base, affectationRepository.findById(b.getId()).orElseThrow().getTrendPreset().getId());
        List<AdminActionLog> audit = presetAudit();
        assertEquals(audited + 1, audit.size());
        assertEquals("trend-preset:" + base, audit.getLast().getTarget());
        assertEquals("root", audit.getLast().getActor());
        JsonNode payload = om.readTree(audit.getLast().getPayload());
        assertEquals(2, payload.get("affectations").asInt());
        assertTrue(payload.get("reset").asBoolean());
        assertTrue(payload.at("/changes/confirm").asText().contains("→"));
        assertFalse(payload.has("provider"));

        // même chose pour un Rainbow système (le Bull de l'actif est le preset UP des deux Affectations)
        withState(alice);
        long bull = systemRainbowId("BTC", "RAINBOW_BULL");
        ObjectNode rb = rainbowUpdate(bull);
        ((ObjectNode) rb.get("tuning")).put("cooldownDays", rb.at("/tuning/cooldownDays").asInt() + 1);
        putJson("/api/expert/rainbow-presets/" + bull, rb).andExpect(status().isOk());
        assertEquals(audited + 2, presetAudit().size());
        assertEquals(2, om.readTree(presetAudit().getLast().getPayload()).get("affectations").asInt());
        assertEquals("rainbow-preset:" + bull, presetAudit().getLast().getTarget());
    }

    @Test
    @DisplayName("Réglages invalides : 400 avec un message par champ, rien n'est écrit")
    void validation() throws Exception {
        long copy = created("/api/expert/trend-presets", java.util.Map.of("assetSymbol", "BTC", "name", "V"));
        ObjectNode body = trendUpdate(copy, "  ");
        ((ObjectNode) body.get("trend")).put("enter", 0.1).put("exit", 0.5).put("shortWindow", 1);
        body.put("rangeMapping", "NOPE");
        putJson("/api/expert/trend-presets/" + copy, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.details.exit").exists()).andExpect(jsonPath("$.details.shortWindow").exists())
                .andExpect(jsonPath("$.details.rangeMapping").exists());
        assertEquals(1, getJson("/api/expert/trend-presets/" + copy).at("/header/revision").asInt());

        long rb = created("/api/expert/rainbow-presets", java.util.Map.of("assetSymbol", "BTC", "name", "R"));
        ObjectNode r = rainbowUpdate(rb);
        r.put("analysisWindowMonths", 0);
        ((ObjectNode) r.get("tuning")).put("sellFraction", 1.5).put("atrMultDown2", -1);
        ((ObjectNode) r.get("globals")).put("baseAmount", 0).put("multX1", -2).put("moonReservePct", 120);
        putJson("/api/expert/rainbow-presets/" + rb, r).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.analysisWindowMonths").exists()).andExpect(jsonPath("$.details.sellFraction").exists())
                .andExpect(jsonPath("$.details.atrMultDown2").exists()).andExpect(jsonPath("$.details['globals.baseAmount']").exists())
                .andExpect(jsonPath("$.details['globals.multX1']").exists()).andExpect(jsonPath("$.details['globals.moonReservePct']").exists());
    }

    // ---------------------------------------------------------------- suppression

    @Test
    @DisplayName("Suppression : preset USER installé ⇒ 409 avec les Affectations (actif, rôle) ; libre ⇒ 204 et disparu")
    void deleteRules() throws Exception {
        long copy = created("/api/expert/rainbow-presets", java.util.Map.of("assetSymbol", "BTC", "name", "D"));
        AssetAffectation mine = editable(alice);
        long trend = mine.getTrendPreset().getId();
        long up = mine.getRainbowUp().getId();
        long down = mine.getRainbowDown().getId();
        affectationService.update(alice, mine.getId(), null, trend, copy, down);
        mvc.perform(delete("/api/expert/rainbow-presets/" + copy)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRESET_IN_USE"))
                .andExpect(jsonPath("$.details[0].assetSymbol").value("BTC")).andExpect(jsonPath("$.details[0].role").value("RAINBOW_UP"));
        mvc.perform(get("/api/expert/rainbow-presets/" + copy)).andExpect(status().isOk());
        affectationService.update(alice, mine.getId(), null, trend, up, down);
        mvc.perform(delete("/api/expert/rainbow-presets/" + copy)).andExpect(status().isNoContent());
        mvc.perform(get("/api/expert/rainbow-presets/" + copy)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- Affectations

    @Test
    @DisplayName("Affectations : lecture (Système créées sur les presets de base), création / renommage / changement de presets (purge moteur) / suppression, Système en lecture seule, nom pris ⇒ 409, preset d'un autre ⇒ 404, ids manquants ⇒ 400")
    void affectations() throws Exception {
        mvc.perform(get("/api/expert/affectations")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].assetSymbol").value("BTC")).andExpect(jsonPath("$[0].complete").value(true))
                .andExpect(jsonPath("$[0].trend.origin").value("SYSTEM")).andExpect(jsonPath("$[0].providerGaps.length()").value(3))
                .andExpect(jsonPath("$[0].providerGaps[0].gap").value(false));
        AssetAffectation a = withState(alice);
        long mineId = created("/api/expert/rainbow-presets", java.util.Map.of("assetSymbol", "BTC", "name", "Pour affecter"));
        long trend = systemTrendId("BTC");
        long bear = systemRainbowId("BTC", "RAINBOW_BEAR");

        java.util.Map<String, Object> body = java.util.Map.of("trendPresetId", trend, "rainbowUpId", mineId, "rainbowDownId", bear);
        putJson("/api/expert/affectations/" + a.getId(), body).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AFFECTATION_SYSTEM_READ_ONLY"));
        mvc.perform(delete("/api/expert/affectations/" + a.getId())).andExpect(status().isForbidden());

        java.util.Map<String, Object> create = new java.util.HashMap<>(body);
        create.put("assetSymbol", "BTC");
        create.put("name", "Ma stratégie");
        JsonNode mine = json(postJson("/api/expert/affectations", create).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ma stratégie")).andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.rainbowUp.id").value(mineId)).andExpect(jsonPath("$.enabled").value(false)));
        long id = mine.get("id").asLong();
        postJson("/api/expert/affectations", create).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AFFECTATION_NAME_TAKEN"));
        mvc.perform(get("/api/expert/affectations").param("asset", "BTC")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        AssetAffectation custom = affectationRepository.findById(id).orElseThrow();
        stateRepository.save(RainbowLiveEngineState.of(custom, DAY, RainbowAtrState.initial()));
        em.flush();
        java.util.Map<String, Object> change = java.util.Map.of("name", "Renommée", "trendPresetId", trend, "rainbowUpId", bear, "rainbowDownId", bear);
        putJson("/api/expert/affectations/" + id, change).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renommée"))
                .andExpect(jsonPath("$.rainbowUp.id").value(bear));
        assertEquals(0, stateRepository.findAll().stream().filter(s -> s.getAffectation().getId().equals(id)).count(), "changement de presets : moteur purgé");
        mvc.perform(get("/api/expert/affectations/" + id)).andExpect(jsonPath("$.rainbowUp.name").exists());

        as(bob, false, DisplayMode.EXPERT);
        mvc.perform(get("/api/expert/affectations/" + id)).andExpect(status().isNotFound());
        postJson("/api/expert/affectations", create).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRESET_NOT_FOUND"));
        postJson("/api/expert/affectations", java.util.Map.of("assetSymbol", "BTC", "name", "X", "trendPresetId", trend)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.rainbowUpId").exists());
        postJson("/api/expert/affectations", java.util.Map.of("assetSymbol", "DOGE", "name", "X", "trendPresetId", trend, "rainbowUpId", bear, "rainbowDownId", bear))
                .andExpect(status().isBadRequest());

        as(alice, false, DisplayMode.EXPERT);
        mvc.perform(delete("/api/expert/affectations/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/expert/affectations/" + id)).andExpect(status().isNotFound());
    }
}
