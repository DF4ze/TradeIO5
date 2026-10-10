package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** Sécurité par méthode réellement active : aucun endpoint des bindings (dont path-quote) sans authentification. */
@SpringJUnitConfig(RainbowLiveBindingControllerSecurityTest.Config.class)
@DisplayName("RainbowLiveBindingController : accès réservé aux utilisateurs authentifiés")
class RainbowLiveBindingControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        RainbowLiveBindingController controller() {
            return new RainbowLiveBindingController(mock(RainbowLiveBindingService.class), mock(BindingPathQuoteService.class),
                    mock(IAuthenticationFacade.class));
        }
    }

    @Autowired
    private RainbowLiveBindingController controller;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("@PreAuthorize(isAuthenticated()) posé au niveau classe")
    void annotationPresent() {
        assertEquals("isAuthenticated()", RainbowLiveBindingController.class.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    @DisplayName("Sans contexte de sécurité : path-quote refusé")
    void noAuthentication() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.pathQuote(1L, BigDecimal.TEN, null));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.list());
    }

    @Test
    @DisplayName("Utilisateur anonyme : path-quote refusé ; authentifié : l'appel passe")
    void anonymousAndAuthenticated() {
        SecurityContextHolder.setContext(new SecurityContextImpl(new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
        assertThrows(AccessDeniedException.class, () -> controller.pathQuote(1L, BigDecimal.TEN, null));

        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                "alice", "x", AuthorityUtils.createAuthorityList("ROLE_USER"))));
        // services mockés => résultat nul => NPE de mapping, mais jamais un refus de sécurité
        try {
            controller.pathQuote(1L, BigDecimal.TEN, null);
        } catch (AccessDeniedException | AuthenticationCredentialsNotFoundException e) {
            throw new AssertionError("l'utilisateur authentifié ne doit pas être refusé", e);
        } catch (RuntimeException expected) {
            assertDoesNotThrow(() -> controller.list());
        }
    }
}
