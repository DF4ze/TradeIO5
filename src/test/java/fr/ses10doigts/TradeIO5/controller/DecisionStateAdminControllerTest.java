package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.decision.Decision;
import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionEngine;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan de test manuel Palier 3 (Clem, 2026-08-17). Même patron MockMvc standalone que les autres
 * controllers admin du palier — {@code @PreAuthorize} non exercé ici (standalone, hors chaîne de
 * sécurité Spring).
 */
@DisplayName("DecisionStateAdminController")
@ExtendWith(MockitoExtension.class)
class DecisionStateAdminControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Mock
    private ScenarioEngine scenarioEngine;
    @Mock
    private DecisionEngine decisionEngine;
    @Mock
    private DomainClock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // lenient() : les tests /decisions n'appellent jamais clock.now() (seul /scenarios en a
        // besoin) — sans lenient(), Mockito (stubs stricts par défaut avec MockitoExtension) rejette
        // ce stub comme "unnecessary" pour ces tests-là.
        lenient().when(clock.now()).thenReturn(NOW);
        DecisionStateAdminController controller = new DecisionStateAdminController(scenarioEngine, decisionEngine, clock);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private MarketScenario scenario(ScenarioOwner owner, String symbol) {
        MarketScenario scenario = mock(MarketScenario.class);
        when(scenario.getId()).thenReturn("scn-1");
        when(scenario.getOwner()).thenReturn(owner);
        when(scenario.getType()).thenReturn(ScenarioType.TREND_UP);
        when(scenario.getSymbol()).thenReturn(Optional.ofNullable(symbol));
        when(scenario.getScope()).thenReturn(OpinionScope.LOCAL);
        ScenarioState state = new ScenarioState(ScenarioType.TREND_UP, NOW);
        state.setStatus(ScenarioStatus.VALIDATED);
        state.setConfidence(0.9);
        state.setStable(true);
        when(scenario.getState()).thenReturn(state);
        return scenario;
    }

    @Test
    @DisplayName("GET /scenarios sans owner délègue à getAllActiveScenarios")
    void getActiveScenarios_withoutOwner_delegatesToGetAll() throws Exception {
        ScenarioOwner owner = ScenarioOwner.user("1");
        // Construit le mock AVANT le when() ci-dessous : l'appeler en argument direct de
        // .thenReturn(...) imbriquerait un when()/mock() dans un stubbing déjà en cours côté
        // scenarioEngine, ce que Mockito refuse ("UnfinishedStubbing").
        MarketScenario scn = scenario(owner, "BTC");
        when(scenarioEngine.getAllActiveScenarios(any(Duration.class), eq(NOW)))
                .thenReturn(List.of(scn));

        mockMvc.perform(get("/api/admin/decision/scenarios"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "[{\"id\":\"scn-1\",\"owner\":\"1\",\"scenarioType\":\"TREND_UP\",\"symbol\":\"BTC\","
                                + "\"scope\":\"LOCAL\",\"status\":\"VALIDATED\",\"confidence\":0.9,\"stable\":true}]"));

        verify(scenarioEngine, never()).getActiveScenarios(any(), any(), any());
    }

    @Test
    @DisplayName("GET /scenarios?owner=1 délègue à getActiveScenarios(owner, ...)")
    void getActiveScenarios_withOwner_delegatesToOwnerScoped() throws Exception {
        ScenarioOwner owner = ScenarioOwner.user("1");
        MarketScenario scn = scenario(owner, "BTC");
        when(scenarioEngine.getActiveScenarios(eq(owner), any(Duration.class), eq(NOW)))
                .thenReturn(List.of(scn));

        mockMvc.perform(get("/api/admin/decision/scenarios").param("owner", "1"))
                .andExpect(status().isOk());

        verify(scenarioEngine, times(1)).getActiveScenarios(eq(owner), any(Duration.class), eq(NOW));
        verify(scenarioEngine, never()).getAllActiveScenarios(any(), any());
    }

    private Decision decision(ScenarioOwner owner, String symbol) {
        DecisionSnapshot snapshot = new DecisionSnapshot(UUID.randomUUID().toString(), symbol, owner, DecisionType.ENTER, NOW);
        ActionStep step = new ActionStep(UUID.randomUUID().toString(), ExecutionAction.BUY, java.math.BigDecimal.ONE, null);
        return new Decision(snapshot, List.of(step));
    }

    @Test
    @DisplayName("GET /decisions sans owner renvoie toutes les décisions actives")
    void getActiveDecisions_withoutOwner_returnsAll() throws Exception {
        ScenarioOwner ownerA = ScenarioOwner.user("1");
        ScenarioOwner ownerB = ScenarioOwner.user("2");
        when(decisionEngine.getAllActiveDecisions()).thenReturn(List.of(decision(ownerA, "BTC"), decision(ownerB, "ETH")));

        mockMvc.perform(get("/api/admin/decision/decisions"))
                .andExpect(status().isOk());

        verify(decisionEngine, times(1)).getAllActiveDecisions();
    }

    @Test
    @DisplayName("GET /decisions?owner=1 filtre côté contrôleur, ne renvoie que les décisions de cet owner")
    void getActiveDecisions_withOwner_filtersInController() throws Exception {
        ScenarioOwner ownerA = ScenarioOwner.user("1");
        ScenarioOwner ownerB = ScenarioOwner.user("2");
        when(decisionEngine.getAllActiveDecisions()).thenReturn(List.of(decision(ownerA, "BTC"), decision(ownerB, "ETH")));

        mockMvc.perform(get("/api/admin/decision/decisions").param("owner", "1"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "[{\"owner\":\"1\",\"symbol\":\"BTC\",\"type\":\"ENTER\",\"status\":\"CREATED\"}]"));
    }
}
