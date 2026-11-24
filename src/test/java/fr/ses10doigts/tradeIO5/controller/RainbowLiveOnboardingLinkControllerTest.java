package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.connector.credential.ExchangeKeysService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkOutcome;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkResult;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("POST /api/rainbow-live/onboarding/link : seuls les actifs demandés sont liés")
class RainbowLiveOnboardingLinkControllerTest {

    private final RainbowLiveOnboardingService onboarding = mock(RainbowLiveOnboardingService.class);
    private final User user = new User();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
        when(facade.getConnectedUser()).thenReturn(user);
        RainbowLiveOnboardingController controller = new RainbowLiveOnboardingController(mock(RainbowLiveBindingService.class),
                mock(BindingPathQuoteService.class), onboarding, facade, mock(RainbowModulationControlService.class), mock(ExchangeKeysService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new RainbowLiveControllerAdvice()).build();
    }

    @Test
    @DisplayName("assets transmis tels quels au service ; réponse = résultat par actif")
    void linksRequestedAssets() throws Exception {
        when(onboarding.link(eq(user), eq(5L), any())).thenReturn(List.of(new LinkResult("DOGE", LinkOutcome.LINKED, 9L, "DOGE Système", "OK", null)));

        mvc.perform(post("/api/rainbow-live/onboarding/link").contentType(MediaType.APPLICATION_JSON).content("{\"walletId\":5,\"assets\":[\"DOGE\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].assetSymbol").value("DOGE"));
        verify(onboarding).link(user, 5L, List.of("DOGE"));
    }

    @Test
    @DisplayName("aucun actif (absent ou vide) : 400 avec la raison, jamais de périmètre par défaut")
    void noAssetsIsBadRequest() throws Exception {
        when(onboarding.link(eq(user), eq(5L), any())).thenThrow(new IllegalArgumentException("Aucun actif demandé"));

        mvc.perform(post("/api/rainbow-live/onboarding/link").contentType(MediaType.APPLICATION_JSON).content("{\"walletId\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Aucun actif demandé"));
    }
}
