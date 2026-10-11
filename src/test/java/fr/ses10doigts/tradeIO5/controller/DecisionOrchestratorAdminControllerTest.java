package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionOrchestrator;
import fr.ses10doigts.tradeIO5.service.tree.decision.OrchestrationResult;
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
 * Palier 3, étape 7. Même patron que {@link UserArchivalAdminControllerTest} (étape 6) : MockMvc
 * standalone sur le seul controller, ne passe pas par la chaîne de sécurité Spring, donc
 * {@code @PreAuthorize("hasRole('ADMIN')")} n'est pas exercé ici — objectif = vérifier le mapping de
 * la route et la délégation au service.
 */
@DisplayName("DecisionOrchestratorAdminController")
@ExtendWith(MockitoExtension.class)
class DecisionOrchestratorAdminControllerTest {

    @Mock
    private DecisionOrchestrator orchestrator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DecisionOrchestratorAdminController controller = new DecisionOrchestratorAdminController(orchestrator);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /api/admin/decision/orchestrate délègue à runCycle() et renvoie le résultat")
    void triggerCycle_delegatesToService_andReturnsResult() throws Exception {
        Instant runAt = Instant.parse("2026-08-14T00:00:00Z");
        when(orchestrator.runCycle()).thenReturn(new OrchestrationResult(5, 3, 2, 1, runAt));

        mockMvc.perform(post("/api/admin/decision/orchestrate"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"signalsComputed\":5,\"activeUsersFound\":3,\"usersProcessed\":2,"
                                + "\"usersSkippedLocked\":1,\"runAt\":\"2026-08-14T00:00:00Z\"}"));

        verify(orchestrator, times(1)).runCycle();
    }
}
