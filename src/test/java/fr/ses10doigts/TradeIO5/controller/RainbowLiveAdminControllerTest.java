package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService.PassSummary;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc standalone (hors chaîne de sécurité, comme {@link DecisionOrchestratorAdminControllerTest}) : mapping,
 * paramètres, délégation ; le 403 hors ADMIN repose sur {@code @PreAuthorize}, vérifié par réflexion.
 */
@DisplayName("RainbowLiveAdminController")
class RainbowLiveAdminControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T23:55:00Z");

    private final RainbowLiveExecutionService service = mock(RainbowLiveExecutionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new RainbowLiveAdminController(service, new FixedDomainClock(NOW)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
    }

    @Test
    @DisplayName("POST /run?pass=T2355 => runPass(T2355, now, null)")
    void runWithoutDay() throws Exception {
        when(service.runPass(eq(RainbowLivePass.T2355), eq(NOW), isNull()))
                .thenReturn(new PassSummary(RainbowLivePass.T2355, LocalDate.of(2026, 9, 30), 3, 0, 0));

        mockMvc.perform(post("/api/admin/rainbow-live/run").param("pass", "T2355"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"pass\":\"T2355\",\"day\":\"2026-09-30\",\"processed\":3,\"skipped\":0,\"errors\":0}"));
        verify(service).runPass(RainbowLivePass.T2355, NOW, null);
    }

    @Test
    @DisplayName("POST /run?pass=T0005&day=2026-09-20 => jour forcé")
    void runWithDayOverride() throws Exception {
        when(service.runPass(any(), any(), any())).thenReturn(new PassSummary(RainbowLivePass.T0005, LocalDate.of(2026, 9, 20), 1, 0, 0));

        mockMvc.perform(post("/api/admin/rainbow-live/run").param("pass", "T0005").param("day", "2026-09-20"))
                .andExpect(status().isOk());
        verify(service).runPass(RainbowLivePass.T0005, NOW, LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("pass invalide => 400")
    void invalidPass() throws Exception {
        mockMvc.perform(post("/api/admin/rainbow-live/run").param("pass", "X")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Réservé ADMIN : @PreAuthorize(hasRole('ADMIN')) sur la classe")
    void adminOnly() {
        PreAuthorize pa = RainbowLiveAdminController.class.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", pa.value());
    }
}
