package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ExecutionBulkAdminSecurityTest.Config.class)
@DisplayName("Actions groupées de la Console : réservées ADMIN")
class ExecutionBulkAdminSecurityTest {

    static final AdminBulkPlanService SERVICE = mock(AdminBulkPlanService.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ExecutionBulkAdminController bulk() {
            IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
            when(facade.getConnectedUser()).thenReturn(User.builder().id(1L).username("u").build());
            return new ExecutionBulkAdminController(SERVICE, facade);
        }
    }

    @Autowired private ExecutionBulkAdminController bulk;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x",
                AuthorityUtils.createAuthorityList(role))));
    }

    private static ExecutionBulkAdminController.BulkRequest request() {
        return new ExecutionBulkAdminController.BulkRequest(List.of(1L), true, null);
    }

    @Test
    @DisplayName("Annotation hasRole('ADMIN')")
    void annotation() {
        assertEquals("hasRole('ADMIN')", ExecutionBulkAdminController.class.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    @DisplayName("Non connecté : refusé ; ROLE_USER : 403")
    void deniedWithoutAdmin() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> bulk.bulk("run", request()));
        loginAs("ROLE_USER");
        assertThrows(AccessDeniedException.class, () -> bulk.bulk("run", request()));
    }

    @Test
    @DisplayName("ROLE_ADMIN : action insensible à la casse, inconnue => IllegalArgumentException (400)")
    void adminCaseInsensitive() {
        loginAs("ROLE_ADMIN");
        bulk.bulk("Reset", request());
        verify(SERVICE).execute(AdminBulkPlanService.BulkAction.RESET, List.of(1L), true, null, "u");
        assertThrows(IllegalArgumentException.class, () -> bulk.bulk("delete", request()));
    }
}
