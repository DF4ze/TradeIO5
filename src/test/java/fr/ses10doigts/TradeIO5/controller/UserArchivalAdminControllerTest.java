package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.service.tree.decision.ArchivalResult;
import fr.ses10doigts.tradeIO5.service.tree.decision.UserArchivalService;
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
 * Palier 3, étape 6. Même patron que {@code DecisionScenarioSnapshotAdminControllerTest} (étape 4) :
 * MockMvc standalone sur le seul controller, ne passe pas par la chaîne de sécurité Spring, donc
 * {@code @PreAuthorize("hasRole('ADMIN')")} n'est pas exercé ici — objectif = vérifier le mapping de
 * la route et la délégation au service.
 */
@DisplayName("UserArchivalAdminController")
@ExtendWith(MockitoExtension.class)
class UserArchivalAdminControllerTest {

    @Mock
    private UserArchivalService archivalService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserArchivalAdminController controller = new UserArchivalAdminController(archivalService);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /api/admin/decision/archive délègue à archiveInactiveUsers() et renvoie le résultat")
    void triggerArchival_delegatesToService_andReturnsResult() throws Exception {
        Instant archivedAt = Instant.parse("2026-08-14T00:00:00Z");
        when(archivalService.archiveInactiveUsers()).thenReturn(new ArchivalResult(2, archivedAt));

        mockMvc.perform(post("/api/admin/decision/archive"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"archivedCount\":2,\"archivedAt\":\"2026-08-14T00:00:00Z\"}"));

        verify(archivalService, times(1)).archiveInactiveUsers();
    }
}
