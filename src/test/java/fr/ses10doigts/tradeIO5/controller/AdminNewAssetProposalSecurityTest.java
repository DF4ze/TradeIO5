package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService;
import fr.ses10doigts.tradeIO5.service.admin.NewAssetRecomputeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Propositions de nouvel actif (D1b lot 2) : ADMIN uniquement, comme les paramètres de modulation. */
@SpringJUnitConfig(AdminNewAssetProposalSecurityTest.Config.class)
@DisplayName("Propositions nouvel actif : endpoints réservés ADMIN")
class AdminNewAssetProposalSecurityTest {

    static final NewAssetProposalAdminService SERVICE = mock(NewAssetProposalAdminService.class);
    static final NewAssetRecomputeService RECOMPUTE = mock(NewAssetRecomputeService.class);
    static final IAuthenticationFacade FACADE = mock(IAuthenticationFacade.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AdminNewAssetProposalController controller() {
            return new AdminNewAssetProposalController(SERVICE, RECOMPUTE, FACADE);
        }
    }

    @Autowired private AdminNewAssetProposalController controller;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        org.mockito.Mockito.reset(SERVICE, RECOMPUTE, FACADE);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x", AuthorityUtils.createAuthorityList(role))));
    }

    @Test
    @DisplayName("Anonyme et ROLE_USER : liste, Valider, Refuser et Recalculer refusés, service jamais appelé")
    void deniedForNonAdmin() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.list(false));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.recompute("SOL"));
        loginAs("ROLE_USER");
        assertThrows(AccessDeniedException.class, () -> controller.list(false));
        assertThrows(AccessDeniedException.class, () -> controller.validate(1L));
        assertThrows(AccessDeniedException.class, () -> controller.refuse(1L));
        assertThrows(AccessDeniedException.class, () -> controller.recompute("SOL"));
        verify(RECOMPUTE, never()).recompute(any(), any());
        verify(SERVICE, never()).list(anyBoolean());
        verify(SERVICE, never()).validate(anyLong(), any());
        verify(SERVICE, never()).refuse(anyLong(), any());
    }

    @Test
    @DisplayName("ROLE_ADMIN : délègue avec l'auteur connecté")
    void adminAllowed() {
        when(FACADE.getConnectedUser()).thenReturn(User.builder().id(9L).username("root").build());
        loginAs("ROLE_ADMIN");
        controller.list(false);
        controller.validate(5L);
        controller.refuse(6L);
        controller.recompute("SOL");
        verify(RECOMPUTE).recompute("SOL", "root");
        verify(SERVICE).list(false);
        verify(SERVICE).validate(5L, "root");
        verify(SERVICE).refuse(6L, "root");
    }
}
