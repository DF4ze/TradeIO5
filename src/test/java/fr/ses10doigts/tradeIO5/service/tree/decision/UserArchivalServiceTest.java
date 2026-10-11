package fr.ses10doigts.tradeIO5.service.tree.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.repository.decision.DecisionSnapshotRepository;
import fr.ses10doigts.tradeIO5.repository.scenario.ScenarioSnapshotRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultScenarioEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 3, étape 6. Repositories réels via {@code @DataJpaTest} (patron {@code
 * AssetProviderRepositoryTest}), moteurs réels construits directement (patron {@code
 * DecisionScenarioSnapshotServiceTest}/{@code MultiUserIsolationIntegrationTest}).
 */
@DataJpaTest
@DisplayName("UserArchivalService")
class UserArchivalServiceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScenarioSnapshotRepository scenarioSnapshotRepository;

    @Autowired
    private DecisionSnapshotRepository decisionSnapshotRepository;

    private FixedDomainClock clock;
    private DefaultScenarioEngine scenarioEngine;
    private DecisionEngine decisionEngine;
    private DecisionScenarioSnapshotService snapshotService;
    private UserArchivalService archivalService;

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(NOW);
        EventBus eventBus = new EventBus();
        scenarioEngine = new DefaultScenarioEngine(clock, eventBus);
        decisionEngine = new DecisionEngine(clock, eventBus, scenarioEngine);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        snapshotService = new DecisionScenarioSnapshotService(
                scenarioEngine, decisionEngine,
                scenarioSnapshotRepository, decisionSnapshotRepository,
                objectMapper, clock
        );
        archivalService = new UserArchivalService(userRepository, snapshotService, scenarioEngine, decisionEngine, clock);
    }

    private User newUser(String username, Instant lastLogin, Instant archivedAt) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.com")
                .password("hash")
                .roles(new HashSet<>())
                .enabled(true)
                .lastLogin(lastLogin)
                .archivedAt(archivedAt)
                .build());
    }

    private void bringToValidatedAndStable(ScenarioOwner owner, String symbol) {
        ScenarioContext context = new ScenarioContext(owner, Optional.of(symbol), clock, List.of());
        OpinionSignal bullish = new OpinionSignal(
                "OpinionId-" + symbol, Optional.of(symbol), SignalType.BULLISH, SignalType.BULLISH,
                0.95, 0.95, OpinionScope.LOCAL, new HashSet<>(), "reason", clock.now()
        );
        for (int i = 0; i < 4; i++) {
            scenarioEngine.onMarketOpinion(bullish, context);
        }
    }

    @Test
    @DisplayName("archiveInactiveUsers évince les données de l'utilisateur inactif des deux moteurs, laisse l'actif intact, positionne archivedAt uniquement sur l'inactif")
    void archiveInactiveUsers_evictsOnlyInactiveUsersData() {
        User inactiveUser = newUser("inactive", NOW.minus(Duration.ofDays(90)), null);
        User activeUser = newUser("active", NOW.minus(Duration.ofDays(1)), null);

        ScenarioOwner inactiveOwner = ScenarioOwner.of(inactiveUser);
        ScenarioOwner activeOwner = ScenarioOwner.of(activeUser);

        bringToValidatedAndStable(inactiveOwner, "BTC");
        bringToValidatedAndStable(activeOwner, "ETH");

        assertEquals(2, scenarioEngine.getAllActiveScenarios(Duration.ofHours(2), clock.now()).size(),
                "Sanity check : deux scénarios actifs avant archivage");
        assertEquals(2, decisionEngine.getAllActiveDecisions().size(),
                "Sanity check : deux décisions actives avant archivage");

        ArchivalResult result = archivalService.archiveInactiveUsers();

        assertEquals(1, result.archivedCount());

        assertTrue(scenarioEngine.getActiveScenarios(inactiveOwner, Duration.ofHours(2), clock.now()).isEmpty(),
                "Les scénarios de l'utilisateur inactif doivent avoir été évincés");
        assertFalse(scenarioEngine.getActiveScenarios(activeOwner, Duration.ofHours(2), clock.now()).isEmpty(),
                "Les scénarios de l'utilisateur actif ne doivent pas être affectés");

        assertTrue(decisionEngine.getAllActiveDecisions().stream().noneMatch(d -> d.getOwner().equals(inactiveOwner)),
                "Les décisions de l'utilisateur inactif doivent avoir été évincées");
        assertTrue(decisionEngine.getAllActiveDecisions().stream().anyMatch(d -> d.getOwner().equals(activeOwner)),
                "Les décisions de l'utilisateur actif ne doivent pas être affectées");

        User reloadedInactive = userRepository.findById(inactiveUser.getId()).orElseThrow();
        User reloadedActive = userRepository.findById(activeUser.getId()).orElseThrow();
        assertNotNull(reloadedInactive.getArchivedAt(), "archivedAt doit être positionné sur l'utilisateur inactif");
        assertNull(reloadedActive.getArchivedAt(), "archivedAt ne doit pas être touché pour l'utilisateur actif");

        // La photo globale (prise avant éviction) doit avoir persisté l'état des deux owners.
        assertEquals(2, scenarioSnapshotRepository.count());
        assertEquals(2, decisionSnapshotRepository.count());
    }

    @Test
    @DisplayName("Un utilisateur déjà archivedAt != null n'est pas re-traité")
    void archiveInactiveUsers_doesNotReprocessAlreadyArchivedUser() {
        Instant alreadyArchivedAt = NOW.minus(Duration.ofDays(10));
        User alreadyArchived = newUser("alreadyArchived", NOW.minus(Duration.ofDays(90)), alreadyArchivedAt);

        ArchivalResult result = archivalService.archiveInactiveUsers();

        assertEquals(0, result.archivedCount());
        User reloaded = userRepository.findById(alreadyArchived.getId()).orElseThrow();
        assertEquals(alreadyArchivedAt, reloaded.getArchivedAt(),
                "archivedAt ne doit pas être écrasé pour un utilisateur déjà archivé");
    }
}
