package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerRunRequest;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Endpoints de l'optimiseur : ADMIN uniquement, bean absent (donc 404) tant que la propriété n'est pas activée. */
class AdminOptimizerSecurityTest {

    static final OptimizerService SERVICE = mock(OptimizerService.class);
    static final IAuthenticationFacade FACADE = mock(IAuthenticationFacade.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        OptimizerService service() {
            return SERVICE;
        }

        @Bean
        IAuthenticationFacade facade() {
            return FACADE;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class, AdminOptimizerController.class);

    private static final OptimizerRunRequest REQUEST = new OptimizerRunRequest("BTC", 1L, 5000.0, LocalDate.parse("2026-09-01"), "BALANCED",
            10.0, null, null);

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        reset(SERVICE, FACADE);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x",
                AuthorityUtils.createAuthorityList(role))));
    }

    @Test
    void controllerIsAbsentWhenDisabledOrUnset() {
        runner.run(ctx -> assertTrue(ctx.getBeansOfType(AdminOptimizerController.class).isEmpty()));
        runner.withPropertyValues("tradeio.optimizer.enabled=false")
                .run(ctx -> assertTrue(ctx.getBeansOfType(AdminOptimizerController.class).isEmpty()));
    }

    @Test
    void nonAdminIsRefusedAndNothingStarts() {
        runner.withPropertyValues("tradeio.optimizer.enabled=true").run(ctx -> {
            AdminOptimizerController controller = ctx.getBean(AdminOptimizerController.class);
            assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.start(REQUEST));
            loginAs("ROLE_USER");
            assertThrows(AccessDeniedException.class, () -> controller.start(REQUEST));
            assertThrows(AccessDeniedException.class, () -> controller.start(OptimizerRunRequest.policyL2(null, null, null, null, null, null)));
            assertThrows(AccessDeniedException.class, () -> controller.get("x"));
            assertThrows(AccessDeniedException.class, () -> controller.cancel("x"));
            verify(SERVICE, never()).start(any(), any());
        });
    }

    @Test
    void adminStartsWithTheConnectedUserAndGets202() {
        User root = User.builder().id(9L).username("root").build();
        OptimizerService.RunView view = new OptimizerService.RunView("id1", "RUNNING", 0.0, "démarrage", null, null, null);
        when(FACADE.getConnectedUser()).thenReturn(root);
        when(SERVICE.start(root, REQUEST)).thenReturn(view);
        runner.withPropertyValues("tradeio.optimizer.enabled=true").run(ctx -> {
            loginAs("ROLE_ADMIN");
            var response = ctx.getBean(AdminOptimizerController.class).start(REQUEST);
            assertEquals(202, response.getStatusCode().value());
            assertEquals("id1", response.getBody().id());
            verify(SERVICE).start(root, REQUEST);
        });
    }
}
