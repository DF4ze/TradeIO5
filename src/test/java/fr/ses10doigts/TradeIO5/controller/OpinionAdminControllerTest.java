package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisFacade;
import fr.ses10doigts.tradeIO5.service.tree.decision.DefaultLocalOpinionParamsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan de test manuel Palier 3 (Clem, 2026-08-17). Même patron MockMvc standalone que
 * {@code DecisionOrchestratorAdminControllerTest} — ne passe pas par la chaîne de sécurité Spring,
 * {@code @PreAuthorize} non exercé ici.
 */
@DisplayName("OpinionAdminController")
@ExtendWith(MockitoExtension.class)
class OpinionAdminControllerTest {

    @Mock
    private TreeAnalysisFacade facade;
    @Mock
    private DefaultLocalOpinionParamsProvider localOpinionParamsProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpinionAdminController controller = new OpinionAdminController(facade, localOpinionParamsProvider);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private OpinionSignal signal(Optional<String> symbol, OpinionScope scope) {
        return new OpinionSignal(
                "op-1", symbol, SignalType.BULLISH, SignalType.BULLISH,
                0.8, 0.8, scope, Set.of("TrendConfirmation"), "reason",
                Instant.parse("2026-08-17T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /opinion?scope=LOCAL utilise les params par défaut de DefaultLocalOpinionParamsProvider")
    void getOpinion_localScope_usesSharedDefaultParams() throws Exception {
        MarketOpinionParameters localParams = MarketOpinionParameters.builder().build();
        when(localOpinionParamsProvider.build()).thenReturn(localParams);
        when(facade.getOpinion(eq("BTC"), eq(OpinionScope.LOCAL), eq(localParams)))
                .thenReturn(signal(Optional.of("BTC"), OpinionScope.LOCAL));

        mockMvc.perform(get("/api/admin/decision/opinion").param("symbol", "BTC").param("scope", "LOCAL"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"opinionId\":\"op-1\",\"symbol\":\"BTC\",\"majoritySignal\":\"BULLISH\","
                                + "\"weightedSignal\":\"BULLISH\",\"confidence\":0.8,\"score\":0.8,"
                                + "\"scope\":\"LOCAL\",\"reason\":\"reason\"}"));

        verify(localOpinionParamsProvider, times(1)).build();
    }

    @Test
    @DisplayName("GET /opinion?scope=GLOBAL n'utilise jamais DefaultLocalOpinionParamsProvider, symbol=null en sortie")
    void getOpinion_globalScope_neverUsesLocalParamsProvider_andSymbolIsNullInResponse() throws Exception {
        ArgumentCaptor<MarketOpinionParameters> paramsCaptor = ArgumentCaptor.forClass(MarketOpinionParameters.class);
        when(facade.getOpinion(eq("BTC"), eq(OpinionScope.GLOBAL), paramsCaptor.capture()))
                .thenReturn(signal(Optional.empty(), OpinionScope.GLOBAL));

        mockMvc.perform(get("/api/admin/decision/opinion").param("symbol", "BTC").param("scope", "GLOBAL"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"scope\":\"GLOBAL\",\"symbol\":null}"));

        verify(localOpinionParamsProvider, never()).build();
        assertTrue(paramsCaptor.getValue().getStrategies() == null || paramsCaptor.getValue().getStrategies().isEmpty(),
                "GLOBAL ne doit jamais recevoir les strategies LOCAL par défaut");
    }
}
