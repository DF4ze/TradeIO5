package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Sécurité par méthode réellement active ({@code @EnableMethodSecurity}) : sans authentification, aucun endpoint du
 * contrôleur n'est exécuté (un contrôleur sans {@code @PreAuthorize} serait de facto public).
 */
@SpringJUnitConfig(RainbowLiveControllerSecurityTest.Config.class)
@DisplayName("RainbowLiveController : accès réservé aux utilisateurs authentifiés")
class RainbowLiveControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        RainbowLiveController controller() {
            return new RainbowLiveController(mock(RainbowLivePresetService.class), mock(RainbowLiveQueryService.class),
                    mock(IAuthenticationFacade.class));
        }
    }

    @Autowired
    private RainbowLiveController controller;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("@PreAuthorize(isAuthenticated()) posé au niveau classe")
    void annotationPresent() {
        var pa = RainbowLiveController.class.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertEquals("isAuthenticated()", pa.value());
    }

    @Test
    @DisplayName("Sans contexte de sécurité : refus (non authentifié)")
    void noAuthentication() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.defaults());
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.presets(null));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.delete(1L));
    }

    @Test
    @DisplayName("Utilisateur anonyme : accès refusé")
    void anonymous() {
        SecurityContextHolder.setContext(new SecurityContextImpl(new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
        assertThrows(AccessDeniedException.class, () -> controller.defaults());
        assertThrows(AccessDeniedException.class, () -> controller.runs(1L, null, null));
        assertThrows(AccessDeniedException.class, () -> controller.performance(1L, null));
    }

    @Test
    @DisplayName("Utilisateur authentifié : l'appel passe")
    void authenticated() {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                "alice", "x", AuthorityUtils.createAuthorityList("ROLE_USER"))));
        assertNull(controller.defaults());   // service mocké => null, mais pas de refus
    }
}
