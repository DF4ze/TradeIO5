package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pas de patron {@code @WebMvcTest} préexistant dans le projet (vérifié avant d'écrire ce test) :
 * MockMvc en mode standalone sur le seul controller, {@link IAuthenticationFacade} et
 * {@link UserTradingSettingsService} mockés directement. Ne passe pas par la chaîne de sécurité
 * Spring (donc {@code @PreAuthorize} n'est pas exercé ici) : objectif = vérifier le mapping des
 * routes et la délégation au service, pas l'authentification elle-même.
 */
@DisplayName("UserTradingSettingsController")
@ExtendWith(MockitoExtension.class)
class UserTradingSettingsControllerTest {

    @Mock
    private UserTradingSettingsService userTradingSettingsService;

    @Mock
    private IAuthenticationFacade authenticationFacade;

    private MockMvc mockMvc;
    private final User user = User.builder().id(1L).username("alice").build();

    @BeforeEach
    void setUp() {
        UserTradingSettingsController controller =
                new UserTradingSettingsController(userTradingSettingsService, authenticationFacade);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET retourne le curseur actuel de l'utilisateur connecté")
    void getRiskCursor_returnsCurrentValue() throws Exception {
        when(authenticationFacade.getConnectedUser()).thenReturn(user);
        when(userTradingSettingsService.getRiskCursor(user)).thenReturn(7);

        mockMvc.perform(get("/api/user/risk-cursor"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"riskCursor\": 7}"));
    }

    @Test
    @DisplayName("PUT appelle setRiskCursor avec la valeur fournie")
    void setRiskCursor_delegatesToService() throws Exception {
        when(authenticationFacade.getConnectedUser()).thenReturn(user);

        mockMvc.perform(put("/api/user/risk-cursor").param("riskCursor", "3"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"riskCursor\": 3}"));

        verify(userTradingSettingsService).setRiskCursor(user, 3);
    }
}
