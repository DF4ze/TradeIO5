package fr.ses10doigts.tradeIO5.service.execution.credential;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.exchange.Fill;
import fr.ses10doigts.tradeIO5.service.execution.run.FillRecorder;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({TradeCredentialResolver.class, TradeCredentialService.class, FillRecorder.class, TradeCredentialPersistenceTest.Config.class})
@DisplayName("Credentials TRADE : portée, unicité, chiffrement au repos, résolution ; fills -> Transaction idempotent")
class TradeCredentialPersistenceTest {

    static final String MASTER = SecretCipherTest.newKey();

    @TestConfiguration
    static class Config {
        @Bean
        SecretCipher cipher() {
            return new SecretCipher(MASTER);
        }

        @Bean
        DomainClock clock() {
            return new FixedDomainClock(Instant.parse("2026-10-10T00:00:00Z"));
        }
    }

    @Autowired private ApiCredentialRepository credentialRepository;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TradeCredentialService service;
    @Autowired private TradeCredentialResolver resolver;
    @Autowired private FillRecorder recorder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManager em;

    private User user;
    private WebProvider okx;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder().username("alice").email("alice@example.fr").password("x").enabled(true).build());
        okx = providerRepository.findByCode(WebProviderCode.OKX)
                .orElseGet(() -> providerRepository.save(WebProvider.builder().code(WebProviderCode.OKX).name("OKX")
                        .apiBaseUrl("https://www.okx.com").build()));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("fr.ses10doigts.tradeIO5")).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("fr.ses10doigts.tradeIO5")).detachAppender(logs);
    }

    @Test
    @DisplayName("Portée par défaut READ ; READ et TRADE coexistent ; unicité (user, provider, portée)")
    void scopeAndUniqueness() {
        assertEquals(CredentialScope.READ, ApiCredential.builder().build().getScope());
        credentialRepository.saveAndFlush(ApiCredential.builder().user(user).webProvider(okx).apiKey("a").secretKey("b")
                .passphrase("c").enabled(true).build());
        credentialRepository.saveAndFlush(ApiCredential.builder().user(user).webProvider(okx).scope(CredentialScope.TRADE)
                .apiKey("a").secretKey("b").passphrase("c").enabled(true).build());
        assertThrows(DataIntegrityViolationException.class, () -> credentialRepository.saveAndFlush(ApiCredential.builder()
                .user(user).webProvider(okx).scope(CredentialScope.TRADE).apiKey("x").secretKey("y").passphrase("z").enabled(true).build()));
    }

    @Test
    @DisplayName("Les lectures existantes (READ) ne voient jamais la credential TRADE")
    void readQueriesIgnoreTrade() {
        service.store(user, WebProviderCode.OKX, "tk", "ts", "tp", true);
        assertTrue(credentialRepository.findReadByUserAndEnabledTrue(user).isEmpty());
        assertTrue(credentialRepository.findReadByUserAndWebProvider(user, okx).isEmpty());
    }

    @Test
    @DisplayName("Stockage chiffré en base ; résolution = copie détachée en clair ; rotation = mise à jour, pas de doublon")
    void encryptedAtRestAndResolved() {
        service.store(user, WebProviderCode.OKX, "plain-key", "plain-secret", "plain-pass", true);
        service.store(user, WebProviderCode.OKX, "plain-key2", "plain-secret2", "plain-pass2", true);
        List<String> raw = jdbc.queryForList("select api_key || '|' || secret_key || '|' || passphrase from api_credentials", String.class);
        assertEquals(1, raw.size());
        assertTrue(raw.get(0).split("\\|").length == 3);
        for (String part : raw.get(0).split("\\|")) {
            assertTrue(SecretCipher.isEncrypted(part));
        }
        assertFalse(raw.get(0).contains("plain"));
        ApiCredential resolved = resolver.resolve(user, WebProviderCode.OKX).orElseThrow();
        assertEquals("plain-key2", resolved.getApiKey());
        assertEquals("plain-secret2", resolved.getSecretKey());
        assertEquals("plain-pass2", resolved.getPassphrase());
        assertEquals(CredentialScope.TRADE, resolved.getScope());
        assertTrue(SecretCipher.isEncrypted(credentialRepository.findAll().get(0).getApiKey()), "l'entité gérée reste chiffrée");
    }

    @Test
    @DisplayName("Absente, désactivée, non chiffrée ou altérée => vide ; jamais de secret dans les logs")
    void unresolvable() {
        assertTrue(resolver.resolve(user, WebProviderCode.OKX).isEmpty());
        service.store(user, WebProviderCode.OKX, "plain-key", "plain-secret", "plain-pass", false);
        assertTrue(resolver.resolve(user, WebProviderCode.OKX).isEmpty());
        service.store(user, WebProviderCode.OKX, "plain-key", "plain-secret", "plain-pass", true);
        em.flush();
        jdbc.update("update api_credentials set api_key = 'plain-key'");
        em.clear();
        assertTrue(resolver.resolve(user, WebProviderCode.OKX).isEmpty());
        for (ILoggingEvent e : logs.list) {
            String m = e.getFormattedMessage();
            assertFalse(m.contains("plain-key") || m.contains("plain-secret") || m.contains("plain-pass"), m);
        }
    }

    @Test
    @DisplayName("Sans clé maître : stockage refusé, rien écrit ; provider sans client d'ordres refusé")
    void noMasterKey() {
        TradeCredentialService noKey = new TradeCredentialService(credentialRepository, providerRepository, new SecretCipher(""),
                new FixedDomainClock(Instant.EPOCH));
        assertThrows(MasterKeyMissingException.class, () -> noKey.store(user, WebProviderCode.OKX, "k", "s", "p", true));
        assertEquals(0, credentialRepository.count());
        assertThrows(IllegalArgumentException.class, () -> service.store(user, WebProviderCode.BINANCE, "k", "s", "p", true));
    }

    @Test
    @DisplayName("Fills -> Transaction : créée avec la devise des frais, rejouer les mêmes fills n'en crée aucune")
    void fillsAreIdempotent() {
        Wallet wallet = walletRepository.save(Wallet.builder().name("w").source(WalletSource.EXCHANGE).user(user)
                .webProvider(okx).webProviderCode(WebProviderCode.OKX).enabled(true).build());
        Fill fill = new Fill("T1", "O1", "c1", "BTC-USDC", StepSide.BUY, new BigDecimal("100000"), new BigDecimal("0.001"),
                new BigDecimal("0.000001"), "BTC", Instant.parse("2026-10-10T00:00:00Z"));
        assertEquals(1, recorder.record(wallet.getId(), "BTC", List.of(fill)));
        assertEquals(0, recorder.record(wallet.getId(), "BTC", List.of(fill)));
        List<Transaction> all = transactionRepository.findAll();
        assertEquals(1, all.size());
        assertEquals("BTC", all.get(0).getFeeCurrency());
        assertEquals("OKX:T1", all.get(0).getExternalTransactionId());
    }
}
