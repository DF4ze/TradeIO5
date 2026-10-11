package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.WalletSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.WalletService;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.opinion.WalletSnapshotService;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultScenarioEngine;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Palier 2 (étape 5) : test d'intégration bout-en-bout, délibérément le dernier de ce lot. Combine
 * les étapes 1 ({@link ScenarioOwner#of}) et 3 ({@link WalletSnapshotService}) pour prouver que la
 * chaîne tient réellement avec des données par utilisateur distinctes, pas seulement des
 * {@code ScenarioOwner} isolés comme dans {@code ScenarioOwnerIsolationDataJpaTest} (étape 1).
 * Patron repris de {@code ScenarioEngineIntegrationTest} pour la partie scénario, et
 * {@code DefaultMarketScenarioTest} pour le driving vers VALIDATED.
 * Un échec ici signale une régression d'isolation introduite par les étapes précédentes, pas un
 * bug propre à cette étape.
 */
@DataJpaTest
@DisplayName("Décision - isolation multi-utilisateur bout-en-bout")
class MultiUserIsolationIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    private User userA;
    private User userB;
    private ScenarioOwner ownerA;
    private ScenarioOwner ownerB;
    private FixedDomainClock clock;

    @BeforeEach
    void setUp() {
        userA = userRepository.save(User.builder()
                .username("alice")
                .email("alice@example.com")
                .password("irrelevant")
                .build());
        userB = userRepository.save(User.builder()
                .username("bob")
                .email("bob@example.com")
                .password("irrelevant")
                .build());

        ownerA = ScenarioOwner.of(userA);
        ownerB = ScenarioOwner.of(userB);

        clock = new FixedDomainClock(Instant.parse("2026-01-29T20:00:00Z"));
    }

    @Test
    @DisplayName("Deux WalletSnapshot construits pour deux users distincts ne partagent aucune valeur")
    void walletSnapshots_forTwoDistinctUsers_areDifferent() {
        Wallet walletA = walletRepository.save(Wallet.builder()
                .name("wallet-A")
                .source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.BINANCE)
                .user(userA)
                .build());
        Wallet walletB = walletRepository.save(Wallet.builder()
                .name("wallet-B")
                .source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.KRAKEN)
                .user(userB)
                .build());

        ProviderApiService providerApiService = mock(ProviderApiService.class);
        when(providerApiService.getAllBalances(walletA)).thenReturn(Map.of("BTC", new BigDecimal("1.0")));
        when(providerApiService.getAllBalances(walletB)).thenReturn(Map.of("ETH", new BigDecimal("10.0")));
        when(providerApiService.getMarketPrice(eq(walletA), eq("BTC"), any())).thenReturn(new BigDecimal("50000"));
        when(providerApiService.getMarketPrice(eq(walletB), eq("ETH"), any())).thenReturn(new BigDecimal("3000"));

        WalletService walletService = new WalletService(walletRepository, null);
        WalletSnapshotService walletSnapshotService = new WalletSnapshotService(walletService, providerApiService);

        WalletSnapshot snapshotA = walletSnapshotService.buildSnapshot(userA, "USDC");
        WalletSnapshot snapshotB = walletSnapshotService.buildSnapshot(userB, "USDC");

        assertNotEquals(snapshotA.getBalances(), snapshotB.getBalances());
        assertNotEquals(snapshotA.getTotalValue(), snapshotB.getTotalValue());
        assertEquals(50000.0, snapshotA.getTotalValue());
        assertEquals(30000.0, snapshotB.getTotalValue());
    }

    @Test
    @DisplayName("Un scénario validé pour A produit une Decision pour A, sans jamais devenir visible pour B")
    void scenarioValidatedForUserA_neverVisibleForUserB() {
        EventBus eventBus = new EventBus();

        DefaultScenarioEngine scenarioEngine = new DefaultScenarioEngine(
                clock,
                eventBus
        );

        new DecisionEngine(clock, eventBus, scenarioEngine);

        AtomicReference<DecisionEvent> capturedDecisionEvent = new AtomicReference<>();
        eventBus.subscribe(DecisionEvent.class, capturedDecisionEvent::set);

        ScenarioContext context = new ScenarioContext(ownerA, Optional.of("BTC"), clock, new ArrayList<>());

        // Fait vivre un scénario pour userA jusqu'à VALIDATED (même patron que
        // DefaultMarketScenarioTest#testProposeIntentWhenValidated / bringToValidatedAndStable).
        for (int i = 0; i < 4; i++) {
            scenarioEngine.onMarketOpinion(bullishOpinion(), context);
        }

        List<MarketScenario> activeForA = scenarioEngine.getActiveScenarios(ownerA, Duration.ofDays(1), clock.now());
        assertFalse(activeForA.isEmpty(), "Un scénario doit être actif pour userA");

        // Une Decision a bien été créée pour userA suite à la validation du scénario.
        assertNotNull(capturedDecisionEvent.get(), "Une DecisionEvent doit avoir été émise pour userA");
        assertEquals(ownerA, capturedDecisionEvent.get().getOwner());

        // Isolation : aucun scénario/décision de userA n'est jamais visible côté userB.
        List<MarketScenario> visibleForB = scenarioEngine.getActiveScenarios(ownerB, Duration.ofDays(1), clock.now());
        assertTrue(visibleForB.isEmpty(), "userB ne doit voir aucun scénario de userA");
    }

    private OpinionSignal bullishOpinion() {
        return new OpinionSignal(
                "OpinionId-bullish",
                Optional.of("BTC"),
                SignalType.BULLISH,
                SignalType.BULLISH,
                0.95,
                0.95,
                OpinionScope.LOCAL,
                new HashSet<>(),
                "reason",
                clock.now()
        );
    }
}
