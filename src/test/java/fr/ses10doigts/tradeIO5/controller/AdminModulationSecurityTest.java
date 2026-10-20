package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminModulationService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService.Params;
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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Paramètres de modulation : ADMIN uniquement. */
@SpringJUnitConfig(AdminModulationSecurityTest.Config.class)
@DisplayName("Modulation : endpoints admin réservés ADMIN")
class AdminModulationSecurityTest {

    static final RainbowModulationControlService CONTROL = mock(RainbowModulationControlService.class);
    static final AdminModulationService ADMIN = mock(AdminModulationService.class);
    static final IAuthenticationFacade FACADE = mock(IAuthenticationFacade.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AdminModulationController controller() {
            return new AdminModulationController(CONTROL, ADMIN, FACADE);
        }
    }

    @Autowired private AdminModulationController controller;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        org.mockito.Mockito.reset(CONTROL, ADMIN, FACADE);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x",
                AuthorityUtils.createAuthorityList(role))));
    }

    private static Params params() {
        return Params.of(RainbowModulationControl.defaults(Instant.EPOCH, null));
    }

    @Test
    @DisplayName("Anonyme et ROLE_USER : GET et PUT refusés, rien écrit")
    void deniedForNonAdmin() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.get());
        loginAs("ROLE_USER");
        assertThrows(AccessDeniedException.class, () -> controller.get());
        assertThrows(AccessDeniedException.class, () -> controller.put(params()));
        verify(ADMIN, never()).update(any(), any());
    }

    @Test
    @DisplayName("ROLE_ADMIN : GET renvoie valeur, défaut et bornes ; PUT délègue avec l'auteur")
    void adminAllowed() {
        RainbowModulationControl current = RainbowModulationControl.defaults(Instant.EPOCH, "initializer");
        when(CONTROL.current()).thenReturn(current);
        when(ADMIN.update(any(), any())).thenReturn(current);
        when(FACADE.getConnectedUser()).thenReturn(User.builder().id(9L).username("root").build());
        loginAs("ROLE_ADMIN");

        AdminModulationController.ParameterDto margin = controller.get().parameters().get("regulationMarginPct");
        assertEquals(0, new java.math.BigDecimal("5").compareTo(margin.min()));
        assertEquals(0, new java.math.BigDecimal("50").compareTo(margin.max()));
        assertEquals(0, new java.math.BigDecimal("25").compareTo(margin.defaultValue()));
        assertEquals(8, controller.get().parameters().size());

        controller.put(params());
        verify(ADMIN).update(params(), "root");
    }
}
