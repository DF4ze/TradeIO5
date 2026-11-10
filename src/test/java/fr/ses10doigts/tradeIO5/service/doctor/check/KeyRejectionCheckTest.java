package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.keypair.ProviderKeyPairSupport;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KeyRejectionCheckTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private ApiCredentialRepository repo;
    private TradeCredentialResolver resolver;
    private ProviderKeyPairSupport support;
    private KeyRejectionCheck check;
    private ApiCredential read;

    @BeforeEach
    void setUp() {
        repo = mock(ApiCredentialRepository.class);
        resolver = mock(TradeCredentialResolver.class);
        support = mock(ProviderKeyPairSupport.class);
        when(support.provider()).thenReturn(WebProviderCode.REVOLUT_X);
        when(support.scopes()).thenReturn(Set.of(CredentialScope.READ, CredentialScope.TRADE));
        WebProvider provider = new WebProvider();
        provider.setCode(WebProviderCode.REVOLUT_X);
        User user = User.builder().id(1L).username("clem").build();
        read = ApiCredential.builder().id(10L).user(user).webProvider(provider).scope(CredentialScope.READ).apiKey("k").enabled(true).build();
        when(repo.findByEnabledTrueAndWebProvider_CodeInAndWebProvider_EnabledTrue(any())).thenReturn(List.of(read));
        check = new KeyRejectionCheck(repo, resolver, now::get, List.of(support));
    }

    @Test
    void acceptedKeyIsOk() {
        assertEquals(DoctorStatus.OK, check.run().getFirst().status());
    }

    @Test
    void rejectedKeyIsCritical() {
        doThrow(new CredentialRejectedException("HTTP 401")).when(support).verify(any());
        CheckResult r = check.run().getFirst();
        assertEquals(DoctorStatus.CRIT, r.status());
        assertTrue(r.message().contains("401"));
    }

    @Test
    void probesAtMostOncePerInterval() {
        check.run();
        check.run();
        verify(support, times(1)).verify(any());
        now.set(now.get().plusSeconds(DoctorDefaults.KEY_PROBE_INTERVAL_MINUTES * 60L + 1));
        check.run();
        verify(support, times(2)).verify(any());
    }

    @Test
    void unreachableExchangeKeepsPreviousVerdict() {
        doThrow(new CredentialRejectedException("HTTP 403")).doThrow(new BalanceUnavailableException("timeout")).when(support).verify(any());
        assertEquals(DoctorStatus.CRIT, check.run().getFirst().status());
        now.set(now.get().plusSeconds(DoctorDefaults.KEY_PROBE_INTERVAL_MINUTES * 60L + 1));
        assertEquals(DoctorStatus.CRIT, check.run().getFirst().status());
    }

    @Test
    void unreachableWithoutHistoryIsUnknown() {
        doThrow(new BalanceUnavailableException("timeout")).when(support).verify(any());
        assertEquals(DoctorStatus.UNKNOWN, check.run().getFirst().status());
    }

    @Test
    void unreadableTradeKeyIsCriticalWithoutProbe() {
        read.setScope(CredentialScope.TRADE);
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());
        assertEquals(DoctorStatus.CRIT, check.run().getFirst().status());
        verify(support, never()).verify(any());
    }

    @Test
    void disabledCredentialResolvesItsAlarm() {
        doThrow(new CredentialRejectedException("x")).when(support).verify(any());
        check.run();
        when(repo.findByEnabledTrueAndWebProvider_CodeInAndWebProvider_EnabledTrue(any())).thenReturn(List.of());
        assertTrue(check.run().isEmpty());
    }
}
