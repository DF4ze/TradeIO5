package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionScenarioSnapshotService;
import fr.ses10doigts.tradeIO5.service.tree.decision.SnapshotResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Palier 3, étape 4 (étape 7 du prompt d'implémentation). Pas de patron {@code
 * EtfFlowAdminControllerTest} préexistant (vérifié avant d'écrire ce test, comme demandé — aucun
 * {@code *AdminController} n'a de test dans ce projet à ce jour). MockMvc standalone sur le seul
 * controller (même patron que {@code UserTradingSettingsControllerTest}) : ne passe pas par la
 * chaîne de sécurité Spring, donc {@code @PreAuthorize("hasRole('ADMIN')")} n'est pas exercé ici —
 * objectif = vérifier le mapping de la route et la délégation au service. Signalé dans le rapport
 * final : le rejet sans rôle ADMIN n'est couvert par aucun test dans ce lot, faute d'infrastructure
 * de test de sécurité déjà en place dans le projet à reprendre.
 */
@DisplayName("DecisionScenarioSnapshotAdminController")
@ExtendWith(MockitoExtension.class)
class DecisionScenarioSnapshotAdminControllerTest {

    @Mock
    private DecisionScenarioSnapshotService snapshotService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DecisionScenarioSnapshotAdminController controller = new DecisionScenarioSnapshotAdminController(snapshotService);
        // ObjectMapper explicite (JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS désactivé) : le
        // standalone MockMvc n'utilise pas l'ObjectMapper Spring Boot auto-configuré par défaut
        // (celui-ci sérialise Instant en timestamp numérique sans ce réglage), à la différence du
        // vrai contexte applicatif.
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /api/admin/decision/snapshot délègue à takeSnapshot() et renvoie le résultat")
    void triggerSnapshot_delegatesToService_andReturnsResult() throws Exception {
        Instant snapshotAt = Instant.parse("2026-08-13T10:00:00Z");
        when(snapshotService.takeSnapshot()).thenReturn(new SnapshotResult(2, 1, snapshotAt));

        mockMvc.perform(post("/api/admin/decision/snapshot"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"scenarioCount\":2,\"decisionCount\":1,\"snapshotAt\":\"2026-08-13T10:00:00Z\"}"));

        verify(snapshotService, times(1)).takeSnapshot();
    }
}
