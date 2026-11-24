package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService.Rates;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Taux de base par actif : ADMIN uniquement (liste, enregistrement, défauts, marge k), même contrôle que la modulation. */
@SpringJUnitConfig(AdminBaseRateSecurityTest.Config.class)
@DisplayName("Taux de base : endpoints admin réservés ADMIN")
class AdminBaseRateSecurityTest {

    static final AssetBaseRateService RATES = mock(AssetBaseRateService.class);
    static final RainbowModulationControlService CONTROL = mock(RainbowModulationControlService.class);
    static final AdminBaseRateService ADMIN = mock(AdminBaseRateService.class);
    static final IAuthenticationFacade FACADE = mock(IAuthenticationFacade.class);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AdminBaseRateController controller() {
            return new AdminBaseRateController(RATES, CONTROL, ADMIN, FACADE);
        }
    }

    @Autowired private AdminBaseRateController controller;

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        org.mockito.Mockito.reset(RATES, CONTROL, ADMIN, FACADE);
    }

    private static void loginAs(String role) {
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken("u", "x", AuthorityUtils.createAuthorityList(role))));
    }

    private static Rates rates() {
        return new Rates(new BigDecimal("0.0003"), new BigDecimal("0.0005"), new BigDecimal("0.0008"), new BigDecimal("0.0015"));
    }

    @Test
    @DisplayName("Anonyme et ROLE_USER : GET, PUT, défauts et marge k refusés, aucun service appelé")
    void deniedForNonAdmin() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.list());
        loginAs("ROLE_USER");
        assertThrows(AccessDeniedException.class, () -> controller.list());
        assertThrows(AccessDeniedException.class, () -> controller.put("BTC", rates()));
        assertThrows(AccessDeniedException.class, () -> controller.defaults("BTC"));
        assertThrows(AccessDeniedException.class, () -> controller.putMarginK(new AdminBaseRateController.MarginKRequest(BigDecimal.ONE)));
        verifyNoInteractions(RATES, ADMIN);
        verify(CONTROL, never()).updateMarginK(any(), any());
    }

    @Test
    @DisplayName("ROLE_ADMIN : délègue avec l'auteur connecté")
    void adminAllowed() {
        when(FACADE.getConnectedUser()).thenReturn(User.builder().id(9L).username("root").build());
        when(CONTROL.current()).thenReturn(RainbowModulationControl.defaults(Instant.EPOCH, null));
        when(ADMIN.save("NEWX", rates(), "root")).thenReturn(new AssetBaseRateService.AssetRates("NEWX", rates(), true, true));
        when(ADMIN.resetDefaults("NEWX", "root")).thenReturn(new AssetBaseRateService.AssetRates("NEWX", rates(), false, false));
        loginAs("ROLE_ADMIN");

        controller.list();
        controller.put("NEWX", rates());
        controller.defaults("NEWX");
        controller.putMarginK(new AdminBaseRateController.MarginKRequest(new BigDecimal("1.25")));

        verify(RATES).list();
        verify(ADMIN).save("NEWX", rates(), "root");
        verify(ADMIN).resetDefaults("NEWX", "root");
        verify(ADMIN).updateMarginK(new BigDecimal("1.25"), "root");
    }
}
