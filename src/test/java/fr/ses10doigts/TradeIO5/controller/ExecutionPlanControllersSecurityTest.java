package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanQueryService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
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

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sécurité par méthode réellement active : le déclenchement du plan est ADMIN, la lecture exige une authentification. */
@SpringJUnitConfig(ExecutionPlanControllersSecurityTest.Config.class)
@DisplayName("Plan d'ordres : endpoint admin réservé ADMIN, lecture réservée aux utilisateurs authentifiés")
class ExecutionPlanControllersSecurityTest {

    static final OrderPlanService PLAN_SERVICE = mock(OrderPlanService.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ExecutionPlanAdminController admin() {
            return new ExecutionPlanAdminController(PLAN_SERVICE, new FixedDomainClock(Instant.parse("2026-10-09T23:58:00Z")));
        }

        @Bean
        RainbowLiveExecutionPlanController reader() {
            return new RainbowLiveExecutionPlanController(mock(OrderPlanQueryService.class), mock(IAuthenticationFacade.class),
                    new FixedDomainClock(Instant.parse("2026-10-09T23:58:00Z")));
        }
    }

    @Autowired private ExecutionPlanAdminController admin;
    @Autowired private RainbowLiveExecutionPlanController reader;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x",
                AuthorityUtils.createAuthorityList(role))));
    }

    @Test
    @DisplayName("Annotations : hasRole('ADMIN') sur l'admin, isAuthenticated() sur la lecture")
    void annotations() {
        assertEquals("hasRole('ADMIN')", ExecutionPlanAdminController.class.getAnnotation(PreAuthorize.class).value());
        assertEquals("isAuthenticated()", RainbowLiveExecutionPlanController.class.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    @DisplayName("Sans authentification : tout refusé")
    void anonymous() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> admin.plan(RainbowLivePass.T2355, null));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> reader.plans(null, null));
        assertThrows(AuthenticationCredentialsNotFoundException.class, reader::latest);
    }

    @Test
    @DisplayName("ROLE_USER : lecture permise, déclenchement admin refusé")
    void userRole() {
        loginAs("ROLE_USER");
        assertThrows(AccessDeniedException.class, () -> admin.plan(RainbowLivePass.T2355, null));
        reader.plans(null, null);
    }

    @Test
    @DisplayName("ROLE_ADMIN : déclenchement permis, jour par défaut = jour UTC de la passe")
    void adminRole() {
        loginAs("ROLE_ADMIN");
        admin.plan(RainbowLivePass.T2355, null);
        verify(PLAN_SERVICE).planAll(LocalDate.of(2026, 10, 9), RainbowLivePass.T2355);
        admin.plan(RainbowLivePass.T0005, null);
        verify(PLAN_SERVICE).planAll(LocalDate.of(2026, 10, 8), RainbowLivePass.T0005);
        admin.plan(RainbowLivePass.T2355, LocalDate.of(2026, 10, 1));
        verify(PLAN_SERVICE).planAll(LocalDate.of(2026, 10, 1), RainbowLivePass.T2355);
    }
}
