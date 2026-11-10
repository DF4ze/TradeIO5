package fr.ses10doigts.tradeIO5.service.connector.keypair;

import fr.ses10doigts.tradeIO5.model.dto.admin.PendingKeyDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ProviderPendingKey;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderPendingKeyRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.admin.AdminResourceNotFoundException;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialService;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderKeyPairServiceTest {

    private final AtomicReference<ProviderPendingKey> store = new AtomicReference<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private ProviderPendingKeyRepository repo;
    private TradeCredentialService trade;
    private ReadCredentialService read;
    private AdminActionLogService journal;
    private ProviderKeyPairSupport support;
    private ProviderKeyPairService service;
    private final User user = User.builder().id(1L).username("clem").build();

    private static SecretCipher cipher(boolean configured) {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return new SecretCipher(configured ? Base64.getEncoder().encodeToString(k) : "");
    }

    @BeforeEach
    void setUp() {
        repo = mock(ProviderPendingKeyRepository.class);
        when(repo.findByUserAndProviderCodeAndScope(any(), anyString(), any())).thenAnswer(i -> Optional.ofNullable(store.get()));
        when(repo.save(any())).thenAnswer(i -> {
            store.set(i.getArgument(0));
            return i.getArgument(0);
        });
        doAnswer(i -> {
            store.set(null);
            return null;
        }).when(repo).delete(any());
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.findByCode(WebProviderCode.REVOLUT_X)).thenReturn(Optional.of(new WebProvider()));
        trade = mock(TradeCredentialService.class);
        read = mock(ReadCredentialService.class);
        journal = mock(AdminActionLogService.class);
        support = mock(ProviderKeyPairSupport.class);
        when(support.provider()).thenReturn(WebProviderCode.REVOLUT_X);
        when(support.scopes()).thenReturn(Set.of(CredentialScope.READ, CredentialScope.TRADE));
        when(support.algorithm()).thenReturn("Ed25519");
        when(support.keySize()).thenReturn(0);
        when(support.registrationHint()).thenReturn("hint");
        when(support.fields(anyString(), anyString())).thenCallRealMethod();
        DomainClock clock = now::get;
        service = build(cipher(true), providers, clock);
        ApiCredential saved = new ApiCredential();
        saved.setId(7L);
        saved.setEnabled(true);
        saved.setScope(CredentialScope.TRADE);
        when(trade.store(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(saved);
        when(read.store(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(saved);
    }

    private ProviderKeyPairService build(SecretCipher c, ProviderRepository p, DomainClock clock) {
        return new ProviderKeyPairService(repo, p, c, trade, read, journal, clock, java.util.List.of(support));
    }

    @Test
    void generateEncryptsPrivateKeyAndExposesOnlyPublic() {
        PendingKeyDto dto = service.generate(user, "revolut_x", "trade");
        ProviderPendingKey row = store.get();
        assertTrue(row.getPrivateKeyEnc().startsWith(SecretCipher.PREFIX));
        assertFalse(row.getPrivateKeyEnc().contains("PRIVATE KEY"));
        assertTrue(dto.publicKeyPem().contains("BEGIN PUBLIC KEY"));
        assertFalse(dto.toString().contains("PRIVATE"));
    }

    @Test
    void regenerateReplacesPendingPair() {
        String first = service.generate(user, "REVOLUT_X", "READ").fingerprint();
        String second = service.generate(user, "REVOLUT_X", "READ").fingerprint();
        assertNotEquals(first, second);
        verify(repo, times(2)).save(any());
    }

    @Test
    void generateRequiresMasterKeyAndSupportedTarget() {
        ProviderKeyPairService noKey = build(cipher(false), mock(ProviderRepository.class), now::get);
        assertThrows(MasterKeyMissingException.class, () -> noKey.generate(user, "REVOLUT_X", "READ"));
        assertThrows(IllegalArgumentException.class, () -> service.generate(user, "BINANCE", "READ"));
        assertThrows(IllegalArgumentException.class, () -> service.generate(user, "REVOLUT_X", "nope"));
    }

    @Test
    void promoteStoresCredentialAndDeletesPending() {
        service.generate(user, "REVOLUT_X", "TRADE");
        var dto = service.promote(user, "REVOLUT_X", "TRADE", " key ");
        assertEquals(7L, dto.id());
        verify(support).verify(any());
        verify(trade).store(eq(user), eq(WebProviderCode.REVOLUT_X), eq("key"), contains("PRIVATE KEY"), isNull(), eq(true));
        assertNull(store.get());
    }

    @Test
    void promoteReadUsesReadService() {
        service.generate(user, "REVOLUT_X", "READ");
        service.promote(user, "REVOLUT_X", "READ", "k");
        verify(read).store(eq(user), eq(WebProviderCode.REVOLUT_X), eq("k"), any(), any(), eq(true));
        verifyNoInteractions(trade);
    }

    @Test
    void rejectedKeyStoresNothingAndKeepsPending() {
        service.generate(user, "REVOLUT_X", "TRADE");
        doThrow(new CredentialRejectedException("401")).when(support).verify(any());
        assertThrows(IllegalArgumentException.class, () -> service.promote(user, "REVOLUT_X", "TRADE", "k"));
        verifyNoInteractions(trade);
        assertNotNull(store.get());
    }

    @Test
    void unavailableExchangeStoresNothing() {
        service.generate(user, "REVOLUT_X", "TRADE");
        doThrow(new BalanceUnavailableException("down")).when(support).verify(any());
        assertThrows(KeyVerificationUnavailableException.class, () -> service.promote(user, "REVOLUT_X", "TRADE", "k"));
        verifyNoInteractions(trade);
        assertNotNull(store.get());
    }

    @Test
    void promoteWithoutPendingOrBlankKeyFails() {
        assertThrows(AdminResourceNotFoundException.class, () -> service.promote(user, "REVOLUT_X", "TRADE", "k"));
        assertThrows(IllegalArgumentException.class, () -> service.promote(user, "REVOLUT_X", "TRADE", " "));
    }

    @Test
    void expiredPendingIsRefusedAndDeleted() {
        service.generate(user, "REVOLUT_X", "TRADE");
        now.set(now.get().plus(KeyPairDefaults.PENDING_TTL).plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> service.promote(user, "REVOLUT_X", "TRADE", "k"));
        assertNull(store.get());
        assertTrue(service.pending(user, "REVOLUT_X", "TRADE").isEmpty());
    }

    @Test
    void cancelIsIdempotentAndPurgeDelegates() {
        service.generate(user, "REVOLUT_X", "READ");
        service.cancel(user, "REVOLUT_X", "READ");
        service.cancel(user, "REVOLUT_X", "READ");
        assertNull(store.get());
        when(repo.deleteByExpiresAtBefore(any())).thenReturn(3L);
        assertEquals(3L, service.purgeExpired());
    }

    @Test
    void journalNeverCarriesSecrets() {
        service.generate(user, "REVOLUT_X", "TRADE");
        service.promote(user, "REVOLUT_X", "TRADE", "SECRETKEY123");
        verify(journal, atLeastOnce()).record(anyString(), any(), anyString(), argThat(m -> !m.toString().contains("SECRETKEY123")
                && !m.toString().contains("PRIVATE")), any());
    }
}
