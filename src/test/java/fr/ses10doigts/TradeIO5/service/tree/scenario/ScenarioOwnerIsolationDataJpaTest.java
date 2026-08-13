package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 2 (étape 1) : prouve que la chaîne identité JPA -> {@code ScenarioOwner} -> isolation
 * {@code ScenarioEngine} tient bout en bout avec de vrais {@code User} persistés, pas seulement
 * des chaînes arbitraires ("user1"/"user2") comme dans
 * {@link DefaultScenarioEngineUnitTest#shouldNotExposeOtherUserScenario()}.
 * Cf. {@code MultiUserIsolationIntegrationTest} (étape 5) pour la version combinant en plus
 * {@code WalletSnapshotService}.
 */
@DataJpaTest
@DisplayName("ScenarioOwner - isolation avec des User persistés")
class ScenarioOwnerIsolationDataJpaTest {

    @Autowired
    private UserRepository userRepository;

    private ScenarioOwner ownerA;
    private ScenarioOwner ownerB;
    private FixedDomainClock clock;

    @BeforeEach
    void setUp() {
        User userA = userRepository.save(User.builder()
                .username("alice")
                .email("alice@example.com")
                .password("irrelevant")
                .build());
        User userB = userRepository.save(User.builder()
                .username("bob")
                .email("bob@example.com")
                .password("irrelevant")
                .build());

        ownerA = ScenarioOwner.of(userA);
        ownerB = ScenarioOwner.of(userB);

        clock = new FixedDomainClock(Instant.parse("2026-01-29T20:00:00Z"));
    }

    @Test
    @DisplayName("Un scénario appartenant à un User B réel n'est jamais visible pour l'owner dérivé du User A")
    void shouldNotExposeOtherRealUserScenario() {
        EventBus eventBus = new EventBus();

        DefaultScenarioEngine engine = new DefaultScenarioEngine(
                clock,
                eventBus
        );

        ScenarioDefinition defB = new ScenarioDefinition(
                ScenarioType.CRASH,
                ownerB,
                Optional.empty(),
                OpinionScope.LOCAL,
                clock.now()
        );
        DefaultMarketScenario scenarioB = new DefaultMarketScenario(defB, eventBus);

        ScenarioKey keyB = new ScenarioKey(
                ownerB,
                ScenarioType.CRASH,
                Optional.empty(),
                OpinionScope.LOCAL
        );
        engine.scenarios.put(keyB, scenarioB);

        List<MarketScenario> visibleForA = engine.getActiveScenarios(ownerA, Duration.ofDays(1), clock.now());

        assertTrue(visibleForA.isEmpty());

        List<MarketScenario> visibleForB = engine.getActiveScenarios(ownerB, Duration.ofDays(1), clock.now());
        assertEquals(1, visibleForB.size());
    }
}
