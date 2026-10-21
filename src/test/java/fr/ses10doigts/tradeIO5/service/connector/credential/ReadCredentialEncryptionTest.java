package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({ReadSecretCipherBinder.class, ReadCredentialService.class, ReadCredentialEncryptionMigrator.class,
        TradeCredentialService.class, ReadCredentialEncryptionTest.Config.class})
@DisplayName("Clés READ : chiffrement au repos transparent, migration, saisie utilisateur")
class ReadCredentialEncryptionTest {

    static String newKey() {
        byte[] k = new byte[32];
        new java.security.SecureRandom().nextBytes(k);
        return java.util.Base64.getEncoder().encodeToString(k);
    }

    @TestConfiguration
    static class Config {
        @Bean
        SecretCipher cipher() {
            return new SecretCipher(newKey());
        }

        @Bean
        DomainClock clock() {
            return new FixedDomainClock(Instant.parse("2026-10-21T00:00:00Z"));
        }
    }

    @Autowired private ReadCredentialService service;
    @Autowired private ReadCredentialEncryptionMigrator migrator;
    @Autowired private TradeCredentialService tradeService;
    @Autowired private ApiCredentialRepository repository;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private SecretCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    private User user;
    private WebProvider okx;

    @BeforeEach
    void setUp() {
        ReadSecretConverter.bind(cipher);
        user = userRepository.save(User.builder().username("alice").email("alice@example.fr").password("x").enabled(true).build());
        okx = provider(WebProviderCode.OKX, "OKX");
        provider(WebProviderCode.KRAKEN, "Kraken");
    }

    private WebProvider provider(WebProviderCode code, String name) {
        return providerRepository.findByCode(code).orElseGet(() -> providerRepository
                .save(WebProvider.builder().code(code).name(name).apiBaseUrl("https://example.org").build()));
    }

    private String raw(String column) {
        return jdbc.queryForObject("select " + column + " from api_credentials", String.class);
    }

    @Test
    @DisplayName("Saisie : chiffrée en base (encr:v1:), relue en clair, wallet créé et rattaché")
    void storedEncryptedAndReadBack() {
        ApiCredential saved = service.store(user, WebProviderCode.OKX, "my-key", "my-secret", "my-pass", true);
        em.flush();
        for (String column : new String[]{"api_key", "secret_key", "passphrase"}) {
            assertTrue(raw(column).startsWith(ReadSecretConverter.PREFIX), column);
        }
        assertFalse(raw("api_key").contains("my-key"));
        em.clear();
        ApiCredential read = repository.findById(saved.getId()).orElseThrow();
        assertEquals("my-key", read.getApiKey());
        assertEquals("my-secret", read.getSecretKey());
        assertEquals("my-pass", read.getPassphrase());
        Wallet wallet = walletRepository.findByUserAndName(user, "OKX").orElseThrow();
        assertEquals(saved.getId(), wallet.getCredential().getId());
    }

    @Test
    @DisplayName("Rotation : une seule ligne, nouvelle valeur ; deux saisies ne créent qu'un wallet")
    void rotation() {
        service.store(user, WebProviderCode.OKX, "k1", "s1", "p1", true);
        service.store(user, WebProviderCode.OKX, "k2", "s2", "p2", true);
        em.flush();
        em.clear();
        assertEquals(1, repository.findReadByUserAndEnabledTrue(user).size());
        assertEquals("k2", repository.findReadByUserAndWebProvider(user, okx).orElseThrow().getApiKey());
        assertEquals(1, walletRepository.findByUserAndEnabledTrue(user).size());
    }

    @Test
    @DisplayName("Ancienne clé en clair : lue telle quelle, chiffrée par la migration, migration idempotente")
    void legacyPlaintextMigrated() {
        repository.saveAndFlush(ApiCredential.builder().user(user).webProvider(okx).apiKey("legacy").secretKey("sec")
                .passphrase("pp").enabled(true).build());
        jdbc.update("update api_credentials set api_key = 'legacy', secret_key = 'sec', passphrase = 'pp'");
        em.clear();
        assertEquals("legacy", repository.findReadByUserAndWebProvider(user, okx).orElseThrow().getApiKey());

        assertEquals(1, migrator.migrate());
        assertTrue(raw("api_key").startsWith(ReadSecretConverter.PREFIX));
        em.clear();
        ApiCredential read = repository.findReadByUserAndWebProvider(user, okx).orElseThrow();
        assertEquals("legacy", read.getApiKey());
        assertEquals("pp", read.getPassphrase());
        assertEquals(0, migrator.migrate());
    }

    @Test
    @DisplayName("Valeurs vides jamais chiffrées ; clé TRADE jamais touchée par la migration ni par le convertisseur")
    void emptyAndTradeUntouched() {
        ApiCredential empty = repository.saveAndFlush(ApiCredential.builder().user(user).webProvider(provider(WebProviderCode.COINALYZE, "Coinalyze"))
                .apiKey("").enabled(true).build());
        tradeService.store(user, WebProviderCode.OKX, "tk", "ts", "tp", true);
        em.flush();
        String tradeRawBefore = jdbc.queryForObject("select api_key from api_credentials where scope = 'TRADE'", String.class);
        assertTrue(tradeRawBefore.startsWith(SecretCipher.PREFIX));

        migrator.migrate();
        assertEquals("", jdbc.queryForObject("select api_key from api_credentials where id = ?", String.class, empty.getId()));
        assertEquals(tradeRawBefore, jdbc.queryForObject("select api_key from api_credentials where scope = 'TRADE'", String.class));
        em.clear();
        assertEquals(tradeRawBefore, repository.findByUserAndWebProviderAndScope(user, okx, CredentialScope.TRADE).orElseThrow().getApiKey());
    }

    @Test
    @DisplayName("Valeur chiffrée avec une autre clé : renvoyée brute, sans exception")
    void unreadableReturnedRaw() {
        service.store(user, WebProviderCode.OKX, "k", "s", "p", true);
        em.flush();
        String sealed = raw("api_key");
        ReadSecretConverter.bind(new SecretCipher(newKey()));
        em.clear();
        assertEquals(sealed, repository.findReadByUserAndWebProvider(user, okx).orElseThrow().getApiKey());
    }

    @Test
    @DisplayName("Sans clé maître : saisie refusée (409), aucune écriture ; migration ignorée")
    void noMasterKey() {
        ReadSecretConverter.bind(new SecretCipher(""));
        assertThrows(MasterKeyMissingException.class, () -> service.store(user, WebProviderCode.OKX, "k", "s", "p", true));
        assertTrue(repository.findAll().isEmpty());
        assertEquals(0, migrator.migrate());
    }

    @Test
    @DisplayName("Validation : exchange non géré, champs manquants ; Kraken sans passphrase")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> service.store(user, WebProviderCode.BINANCE, "k", "s", null, true));
        assertThrows(IllegalArgumentException.class, () -> service.store(user, null, "k", "s", null, true));
        assertThrows(IllegalArgumentException.class, () -> service.store(user, WebProviderCode.OKX, "k", "s", " ", true));
        assertThrows(IllegalArgumentException.class, () -> service.store(user, WebProviderCode.KRAKEN, "", "s", null, true));
        ApiCredential kraken = service.store(user, WebProviderCode.KRAKEN, "k", "s", "ignored", true);
        assertNull(kraken.getPassphrase());
    }
}
