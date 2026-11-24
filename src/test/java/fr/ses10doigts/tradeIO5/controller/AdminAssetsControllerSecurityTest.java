package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService;
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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Onglet admin « Actifs » : chaque endpoint est réservé à ROLE_ADMIN (non connecté et ROLE_USER refusés, service jamais appelé). */
@SpringJUnitConfig(AdminAssetsControllerSecurityTest.Config.class)
@DisplayName("Admin Actifs : endpoints réservés ADMIN")
class AdminAssetsControllerSecurityTest {

    static final AdminAssetsService SERVICE = mock(AdminAssetsService.class);
    static final IAuthenticationFacade FACADE = mock(IAuthenticationFacade.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AdminAssetsController controller() {
            return new AdminAssetsController(SERVICE, FACADE);
        }
    }

    @Autowired private AdminAssetsController controller;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        org.mockito.Mockito.reset(SERVICE, FACADE);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x", AuthorityUtils.createAuthorityList(role))));
    }

    @Test
    @DisplayName("Non connecté : tous les endpoints refusés")
    void anonymousDenied() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.overview());
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.blocklist());
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.addToBlocklist(new AdminAssetsController.BlocklistRequest("SOL", null)));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.removeFromBlocklist("SOL"));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.settings());
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.updateSettings(new AdminAssetsController.MinVolumeRequest(BigDecimal.ONE)));
        verifyNoInteractions(SERVICE);
    }

    @Test
    @DisplayName("ROLE_USER : tous les endpoints refusés")
    void userDenied() {
        loginAs("ROLE_USER");
        assertThrows(AccessDeniedException.class, () -> controller.overview());
        assertThrows(AccessDeniedException.class, () -> controller.blocklist());
        assertThrows(AccessDeniedException.class, () -> controller.addToBlocklist(new AdminAssetsController.BlocklistRequest("SOL", null)));
        assertThrows(AccessDeniedException.class, () -> controller.removeFromBlocklist("SOL"));
        assertThrows(AccessDeniedException.class, () -> controller.settings());
        assertThrows(AccessDeniedException.class, () -> controller.updateSettings(new AdminAssetsController.MinVolumeRequest(BigDecimal.ONE)));
        verifyNoInteractions(SERVICE);
    }

    @Test
    @DisplayName("ROLE_ADMIN : délègue avec l'auteur connecté")
    void adminAllowed() {
        when(FACADE.getConnectedUser()).thenReturn(User.builder().id(9L).username("root").build());
        loginAs("ROLE_ADMIN");
        controller.overview();
        controller.blocklist();
        controller.addToBlocklist(new AdminAssetsController.BlocklistRequest("SOL", "motif"));
        controller.removeFromBlocklist("SOL");
        controller.settings();
        controller.updateSettings(new AdminAssetsController.MinVolumeRequest(BigDecimal.TEN));
        verify(SERVICE).overview();
        verify(SERVICE).blocklist();
        verify(SERVICE).addToBlocklist("SOL", "motif", "root");
        verify(SERVICE).removeFromBlocklist("SOL", "root");
        verify(SERVICE).settings();
        verify(SERVICE).updateMinVolume(BigDecimal.TEN, "root");
    }
}
