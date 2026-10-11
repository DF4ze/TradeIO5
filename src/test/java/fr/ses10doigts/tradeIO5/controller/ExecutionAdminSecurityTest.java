package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.admin.AdminCredentialService;
import fr.ses10doigts.tradeIO5.service.admin.AdminExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pilotage de l'exécution : ADMIN uniquement ; binding d'un autre utilisateur => 404 (exception mappée) côté propriétaire. */
@SpringJUnitConfig(ExecutionAdminSecurityTest.Config.class)
@DisplayName("Exécution : endpoints admin réservés ADMIN, binding d'autrui => 404")
class ExecutionAdminSecurityTest {

    static final AdminExecutionService EXECUTOR = mock(AdminExecutionService.class);
    static final ExecutionControlService CONTROL = mock(ExecutionControlService.class);
    static final RainbowLiveBindingService BINDINGS = mock(RainbowLiveBindingService.class);
    static final AdminCredentialService CREDENTIALS = mock(AdminCredentialService.class);
    static final IAuthenticationFacade FACADE = mock(IAuthenticationFacade.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ExecutionAdminController admin() {
            return new ExecutionAdminController(EXECUTOR, CONTROL, CREDENTIALS, FACADE);
        }

        @Bean
        RainbowLiveBindingController bindings() {
            return new RainbowLiveBindingController(BINDINGS, mock(BindingPathQuoteService.class), FACADE);
        }
    }

    @Autowired private ExecutionAdminController admin;
    @Autowired private RainbowLiveBindingController bindings;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        org.mockito.Mockito.reset(EXECUTOR, CONTROL, BINDINGS, CREDENTIALS, FACADE);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x",
                AuthorityUtils.createAuthorityList(role))));
    }

    @Test
    @DisplayName("Anonyme et ROLE_USER : run / kill-switch / arm / credentials refusés, rien d'exécuté")
    void deniedForNonAdmin() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> admin.run(1L));
        loginAs("ROLE_USER");
        assertThrows(AccessDeniedException.class, () -> admin.run(1L));
        assertThrows(AccessDeniedException.class, () -> admin.killSwitch(new ExecutionAdminController.KillSwitchRequest(false)));
        assertThrows(AccessDeniedException.class, () -> admin.arm(1L));
        assertThrows(AccessDeniedException.class, () -> admin.control());
        assertThrows(AccessDeniedException.class, () -> admin.storeCredential(
                new ExecutionAdminController.TradeCredentialRequest(1L, null, "k", "s", "p", true)));
        verify(EXECUTOR, never()).run(anyLong(), any());
        verify(EXECUTOR, never()).arm(anyLong(), any());
        verify(EXECUTOR, never()).setKillSwitch(org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("ROLE_ADMIN : autorisé")
    void adminAllowed() {
        when(FACADE.getConnectedUser()).thenReturn(User.builder().id(9L).username("root").build());
        loginAs("ROLE_ADMIN");
        ResponseEntity<Void> r = admin.arm(3L);
        assertEquals(204, r.getStatusCode().value());
        verify(EXECUTOR).arm(3L, "root");
    }

    @Test
    @DisplayName("Credential : toString de la requête sans aucun secret")
    void requestToStringHasNoSecret() {
        String s = new ExecutionAdminController.TradeCredentialRequest(1L, null, "KEY123", "SECRET456", "PASS789", true).toString();
        for (String secret : new String[]{"KEY123", "SECRET456", "PASS789"}) {
            assertEquals(false, s.contains(secret));
        }
    }

    @Test
    @DisplayName("Utilisateur : activer / confirmer sur le binding d'un autre => 404 propagé par le service")
    void ownerScoped() {
        User me = User.builder().id(1L).build();
        when(FACADE.getConnectedUser()).thenReturn(me);
        when(BINDINGS.setExecution(me, 99L, true)).thenThrow(RainbowLiveBindingNotFoundException.binding(99L));
        when(BINDINGS.confirmFirstLive(me, 99L)).thenThrow(RainbowLiveBindingNotFoundException.binding(99L));
        loginAs("ROLE_USER");
        assertThrows(RainbowLiveBindingNotFoundException.class,
                () -> bindings.setExecution(99L, new RainbowLiveBindingController.ExecutionRequest(true)));
        assertThrows(RainbowLiveBindingNotFoundException.class, () -> bindings.confirmFirstLive(99L));
    }
}
