package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest
@Import({UserPreferencesService.class, UserTradingSettingsService.class, RainbowModulationControlService.class})
@DisplayName("UserPreferencesController : thème, alertes, plafonds personnels, profil de départ")
class UserPreferencesControllerTest {

    private static final String URL = "/api/user/preferences";

    @Autowired private UserPreferencesService service;
    @Autowired private UserTradingSettingsService tradingSettings;
    @Autowired private UserRepository userRepository;

    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private MockMvc mvc;
    private User alice;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        when(facade.getConnectedUser()).thenReturn(alice);
        mvc = MockMvcBuilders.standaloneSetup(new UserPreferencesController(service, facade))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    @Test
    @DisplayName("Défauts : thème sombre, aucun profil, aucune alerte coupée, plafonds absolus seuls")
    void defaults() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("DARK"))
                .andExpect(jsonPath("$.startingProfile").doesNotExist())
                .andExpect(jsonPath("$.alertsDisabled.length()").value(0))
                .andExpect(jsonPath("$.caps.absoluteMaxOrderUsd").value(30.0))
                .andExpect(jsonPath("$.caps.absoluteMaxDayUsd").value(100.0))
                .andExpect(jsonPath("$.caps.maxOrderUsd").doesNotExist());
    }

    @Test
    @DisplayName("PUT : thème, alertes coupées et plafonds enregistrés ; avertissement proche du plafond absolu")
    void putStores() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"LIGHT\",\"alertsDisabled\":[\"TRANSIT_STOCK\"],\"maxOrderUsd\":25,\"maxDayUsd\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("LIGHT"))
                .andExpect(jsonPath("$.alertsDisabled[0]").value("TRANSIT_STOCK"))
                .andExpect(jsonPath("$.caps.maxOrderUsd").value(25.0))
                .andExpect(jsonPath("$.warnings.length()").value(1));
        UserPreferencesService.UserCaps caps = service.caps(alice);
        assertEquals(0, new BigDecimal("25").compareTo(caps.maxOrderUsd()));
        assertEquals(0, new BigDecimal("40").compareTo(caps.maxDayUsd()));
    }

    @Test
    @DisplayName("Plafond au-dessus de l'absolu ou ≤ 0 : 400, rien d'écrit")
    void capsAboveAbsoluteRejected() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"maxOrderUsd\":31}")).andExpect(status().isBadRequest());
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"maxDayUsd\":0}")).andExpect(status().isBadRequest());
        assertNull(service.caps(alice).maxOrderUsd());
    }

    @Test
    @DisplayName("Profil de départ : pose la bande d'exposition, puissance et biais conservés")
    void profileSetsBand() throws Exception {
        mvc.perform(put(URL + "/profile").contentType(MediaType.APPLICATION_JSON).content("{\"profile\":\"PRUDENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("PRUDENT"))
                .andExpect(jsonPath("$.modulation.exposureMin").value(20.0))
                .andExpect(jsonPath("$.modulation.exposureMax").value(50.0))
                .andExpect(jsonPath("$.modulation.power").value(1.0));
        assertEquals(20.0, tradingSettings.getModulationSettings(alice).exposureMin());
        mvc.perform(get(URL)).andExpect(jsonPath("$.startingProfile").value("PRUDENT"));
    }

    @Test
    @DisplayName("Profil absent : 400")
    void profileRequired() throws Exception {
        mvc.perform(put(URL + "/profile").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }
}
