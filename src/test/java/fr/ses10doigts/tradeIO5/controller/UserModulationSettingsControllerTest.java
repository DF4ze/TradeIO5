package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc standalone (la sécurité {@code isAuthenticated} n'est pas exercée ici : mapping, délégation, erreurs 400). */
@DisplayName("UserModulationSettingsController")
class UserModulationSettingsControllerTest {

    private final User user = User.builder().id(1L).username("alice").build();
    private UserTradingSettingsService settings;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        settings = mock(UserTradingSettingsService.class);
        RainbowModulationControlService control = mock(RainbowModulationControlService.class);
        IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
        when(facade.getConnectedUser()).thenReturn(user);
        when(control.current()).thenReturn(RainbowModulationControl.defaults(Instant.EPOCH, null));
        mockMvc = MockMvcBuilders.standaloneSetup(new UserModulationSettingsController(settings, control, facade)).build();
    }

    @Test
    @DisplayName("GET : valeurs stockées + limites admin en vigueur")
    void getReturnsSettingsAndLimits() throws Exception {
        when(settings.getModulationSettings(user)).thenReturn(new ModulationSettings(20, 80, 1.5, 0.2));

        mockMvc.perform(get("/api/user/modulation-settings"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"settings\":{\"exposureMin\":20,\"exposureMax\":80,\"power\":1.5,\"bias\":0.2},"
                        + "\"limits\":{\"minBandWidthPts\":10,\"powerMin\":0.25,\"powerMax\":2,\"biasMax\":0.5}}"));
    }

    @Test
    @DisplayName("PUT : délègue pour l'utilisateur connecté")
    void putDelegates() throws Exception {
        when(settings.setModulationSettings(user, 20, 80, 1.5, 0.2)).thenReturn(new ModulationSettings(20, 80, 1.5, 0.2));

        mockMvc.perform(put("/api/user/modulation-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exposureMin\":20,\"exposureMax\":80,\"power\":1.5,\"bias\":0.2}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"settings\":{\"exposureMin\":20,\"power\":1.5}}"));

        verify(settings).setModulationSettings(user, 20, 80, 1.5, 0.2);
    }

    @Test
    @DisplayName("PUT : erreur de validation => 400 avec message")
    void putValidationErrorIs400() throws Exception {
        when(settings.setModulationSettings(user, 50, 50, 1, 0)).thenThrow(new IllegalArgumentException("bande invalide"));

        mockMvc.perform(put("/api/user/modulation-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exposureMin\":50,\"exposureMax\":50,\"power\":1,\"bias\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT : champ manquant => 400, rien écrit")
    void putMissingFieldIs400() throws Exception {
        mockMvc.perform(put("/api/user/modulation-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exposureMin\":20,\"exposureMax\":80,\"power\":1.5}"))
                .andExpect(status().isBadRequest());
    }
}
