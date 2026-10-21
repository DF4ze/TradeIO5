package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.OwnTradeCredentialRequest;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.service.admin.AdminBindingQueryService;
import fr.ses10doigts.tradeIO5.service.admin.AdminCatalogService;
import fr.ses10doigts.tradeIO5.service.admin.AdminOrderTestService;
import fr.ses10doigts.tradeIO5.service.admin.AdminCredentialService;
import fr.ses10doigts.tradeIO5.service.admin.AdminPlanQueryService;
import fr.ses10doigts.tradeIO5.service.admin.AdminStateService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sécurité par méthode réellement active sur chaque nouveau contrôleur de la page Admin : USER refusé, ADMIN accepté. */
@SpringJUnitConfig(AdminControllersSecurityTest.Config.class)
@DisplayName("Page Admin : contrôleurs réservés ADMIN, saisie propriétaire sur l'utilisateur connecté uniquement")
class AdminControllersSecurityTest {

    static final AdminStateService STATE = mock(AdminStateService.class);
    static final AdminPlanQueryService PLANS = mock(AdminPlanQueryService.class);
    static final AdminActionLogService JOURNAL = mock(AdminActionLogService.class);
    static final AdminCredentialService CREDENTIALS = mock(AdminCredentialService.class);
    static final AdminBindingQueryService BINDINGS = mock(AdminBindingQueryService.class);
    static final RainbowLiveBindingService BINDING_SERVICE = mock(RainbowLiveBindingService.class);
    static final AdminOrderTestService ORDER_TESTS = mock(AdminOrderTestService.class);
    static final AdminCatalogService CATALOG = mock(AdminCatalogService.class);
    static final IAuthenticationFacade FACADE = mock(IAuthenticationFacade.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean AdminExecutionQueryController query() { return new AdminExecutionQueryController(STATE, PLANS); }
        @Bean AdminActionController actions() { return new AdminActionController(JOURNAL); }
        @Bean AdminCredentialController credentials() { return new AdminCredentialController(CREDENTIALS, FACADE); }
        @Bean AdminBindingController bindings() { return new AdminBindingController(BINDINGS); }
        @Bean AdminOrderTestController orderTests() { return new AdminOrderTestController(ORDER_TESTS, FACADE); }
        @Bean AdminCatalogController catalog() { return new AdminCatalogController(CATALOG); }
        @Bean OwnCredentialController own() { return new OwnCredentialController(CREDENTIALS, mock(fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialService.class), FACADE); }
        @Bean RainbowLiveWalletOptionsController wallets() { return new RainbowLiveWalletOptionsController(BINDING_SERVICE, FACADE); }
    }

    @Autowired private AdminExecutionQueryController query;
    @Autowired private AdminActionController actions;
    @Autowired private AdminCredentialController credentials;
    @Autowired private AdminBindingController bindings;
    @Autowired private AdminOrderTestController orderTests;
    @Autowired private AdminCatalogController catalog;
    @Autowired private OwnCredentialController own;
    @Autowired private RainbowLiveWalletOptionsController wallets;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        reset(STATE, PLANS, JOURNAL, CREDENTIALS, BINDINGS, BINDING_SERVICE, FACADE, ORDER_TESTS, CATALOG);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x",
                AuthorityUtils.createAuthorityList(role))));
    }

    private static final OrderTestRequest ORDER = new OrderTestRequest(1L, WebProviderCode.KRAKEN, "BTC-USDC", StepSide.BUY,
            java.math.BigDecimal.ONE, null, java.math.BigDecimal.TEN);

    private List<org.junit.jupiter.api.function.Executable> adminCalls() {
        return List.of(query::state, () -> query.plans(null, null, null, null), () -> query.events(1L, 0, 20),
                () -> actions.actions(0, 20), credentials::list, () -> orderTests.validate(ORDER),
                () -> catalog.instruments(WebProviderCode.KRAKEN, null),
                () -> credentials.setEnabled(1L, new AdminCredentialController.EnabledRequest(true)), bindings::list);
    }

    @Test
    @DisplayName("Anonyme : tout refusé")
    void anonymous() {
        adminCalls().forEach(call -> assertThrows(AuthenticationCredentialsNotFoundException.class, call::execute));
        assertThrows(AuthenticationCredentialsNotFoundException.class, wallets::wallets);
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> own.storeTrade(new OwnTradeCredentialRequest(WebProviderCode.OKX, "k", "s", "p", true)));
    }

    @Test
    @DisplayName("ROLE_USER : tous les endpoints admin refusés, rien d'appelé ; endpoints propriétaire permis")
    void userRole() {
        loginAs("ROLE_USER");
        adminCalls().forEach(call -> assertThrows(AccessDeniedException.class, call::execute));
        org.mockito.Mockito.verifyNoInteractions(STATE, PLANS, JOURNAL, CREDENTIALS, BINDINGS, ORDER_TESTS, CATALOG);
        when(FACADE.getConnectedUser()).thenReturn(User.builder().id(1L).username("u").build());
        wallets.wallets();
    }

    @Test
    @DisplayName("ROLE_ADMIN : tous les endpoints admin permis")
    void adminRole() throws Throwable {
        when(FACADE.getConnectedUser()).thenReturn(User.builder().id(1L).username("root").build());
        loginAs("ROLE_ADMIN");
        for (var call : adminCalls()) {
            call.execute();
        }
        verify(STATE).state();
        verify(JOURNAL).page(0, 20);
        verify(CREDENTIALS).setEnabled(1L, true, "root");
        verify(BINDINGS).list();
    }

    @Test
    @DisplayName("Saisie propriétaire : toujours l'utilisateur connecté (le corps n'a aucun userId), réponse 201 sans secret")
    void ownerStoresOwnCredentialOnly() {
        User me = User.builder().id(5L).username("alice").build();
        when(FACADE.getConnectedUser()).thenReturn(me);
        ApiCredential saved = ApiCredential.builder().id(9L).scope(CredentialScope.TRADE).enabled(true).build();
        when(CREDENTIALS.store(any(), any(), any(), any(), any(), any(), any())).thenReturn(saved);
        loginAs("ROLE_USER");

        ResponseEntity<?> response = own.storeTrade(new OwnTradeCredentialRequest(WebProviderCode.OKX, "k", "s", "p", null));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(CREDENTIALS).store(eq(me), eq("alice"), eq(WebProviderCode.OKX), eq("k"), eq("s"), eq("p"), eq(null));
        assertFalse(Arrays.stream(OwnTradeCredentialRequest.class.getRecordComponents()).map(RecordComponent::getName)
                .anyMatch(name -> name.toLowerCase().contains("user")));
    }

    @Test
    @DisplayName("Annotations de classe : hasRole('ADMIN') sur les contrôleurs admin, isAuthenticated() côté propriétaire")
    void annotations() {
        for (Class<?> admin : List.of(AdminExecutionQueryController.class, AdminActionController.class, AdminCredentialController.class,
                AdminBindingController.class, AdminOrderTestController.class, AdminCatalogController.class, ExecutionAdminController.class, ExecutionPlanAdminController.class)) {
            assertEquals("hasRole('ADMIN')", admin.getAnnotation(PreAuthorize.class).value(), admin.getSimpleName());
            assertTrue(admin.getAnnotation(RequestMapping.class).value()[0].startsWith("/api/admin/"), admin.getSimpleName());
        }
        assertEquals("isAuthenticated()", OwnCredentialController.class.getAnnotation(PreAuthorize.class).value());
        assertEquals("isAuthenticated()", RainbowLiveWalletOptionsController.class.getAnnotation(PreAuthorize.class).value());
    }
}
