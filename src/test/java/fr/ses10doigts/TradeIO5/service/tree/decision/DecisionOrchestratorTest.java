package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisFacade;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultScenarioEngine;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.EtfFlowConfidenceStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Palier 3, étape 7. Couvre {@link DecisionOrchestrator} : calcul des 5 signaux par cycle (3 LOCAL +
 * 1 GLOBAL + 1 MACRO), itération sur les owners actifs, verrou anti-doublon, et — en test
 * d'intégration légère — la cascade complète Opinion → Scenario → Decision sans appel direct à
 * {@link DecisionEngine} (décision 10 du prompt d'implémentation de cette étape).
 */
@DisplayName("DecisionOrchestrator")
class DecisionOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private TreeAnalysisFacade facade;
    private UserRepository userRepository;
    private IndicatorCredentialResolver credentialResolver;
    private TrendConfirmationStrategy trendConfirmationStrategy;
    private MovementQualificationStrategy movementQualificationStrategy;
    private OrderFlowStrategy orderFlowStrategy;
    private EtfFlowConfidenceStrategy etfFlowConfidenceStrategy;

    @BeforeEach
    void setUp() {
        facade = mock(TreeAnalysisFacade.class);
        userRepository = mock(UserRepository.class);
        credentialResolver = mock(IndicatorCredentialResolver.class);
        // Jamais réellement invoquées par DecisionOrchestrator (seulement enveloppées dans un
        // StrategyKey) : des mocks suffisent, aucune de leurs méthodes n'est exercée par ce lot.
        trendConfirmationStrategy = mock(TrendConfirmationStrategy.class);
        movementQualificationStrategy = mock(MovementQualificationStrategy.class);
        orderFlowStrategy = mock(OrderFlowStrategy.class);
        etfFlowConfidenceStrategy = mock(EtfFlowConfidenceStrategy.class);
    }

    private DecisionOrchestrator newOrchestrator(ScenarioEngine scenarioEngine, OwnerRefreshGuard guard, FixedDomainClock clock) {
        return new DecisionOrchestrator(
                facade,
                scenarioEngine,
                userRepository,
                guard,
                credentialResolver,
                trendConfirmationStrategy,
                movementQualificationStrategy,
                orderFlowStrategy,
                etfFlowConfidenceStrategy,
                clock
        );
    }

    private OpinionSignal signal(String opinionId, Optional<String> symbol, OpinionScope scope) {
        return new OpinionSignal(
                opinionId, symbol, SignalType.BULLISH, SignalType.BULLISH,
                0.95, 0.95, scope, Set.of(), "reason", NOW
        );
    }

    private void mockFiveSignals(OpinionSignal btcLocal, OpinionSignal ethLocal, OpinionSignal paxgLocal,
                                  OpinionSignal global, OpinionSignal macro) {
        when(facade.getOpinion(eq("BTC"), eq(OpinionScope.LOCAL), any())).thenReturn(btcLocal);
        when(facade.getOpinion(eq("ETH"), eq(OpinionScope.LOCAL), any())).thenReturn(ethLocal);
        when(facade.getOpinion(eq("PAXG"), eq(OpinionScope.LOCAL), any())).thenReturn(paxgLocal);
        when(facade.getOpinion(eq("BTC"), eq(OpinionScope.GLOBAL), any())).thenReturn(global);
        when(facade.getOpinion(eq("BTC"), eq(OpinionScope.MACRO), any())).thenReturn(macro);
    }

    private User activeUser(long id, String username) {
        return User.builder()
                .id(id).username(username).email(username + "@test.com").password("hash")
                .roles(new HashSet<>()).enabled(true).lastLogin(NOW).archivedAt(null)
                .build();
    }

    @Test
    @DisplayName("runCycle() calcule exactement 5 signaux (3 LOCAL + 1 GLOBAL + 1 MACRO) et les propage à chaque owner actif")
    void runCycle_computesFiveSignals_andPropagatesToEachActiveUser() {
        OpinionSignal btcLocal = signal("BTC-LOCAL", Optional.of("BTC"), OpinionScope.LOCAL);
        OpinionSignal ethLocal = signal("ETH-LOCAL", Optional.of("ETH"), OpinionScope.LOCAL);
        OpinionSignal paxgLocal = signal("PAXG-LOCAL", Optional.of("PAXG"), OpinionScope.LOCAL);
        OpinionSignal global = signal("GLOBAL", Optional.empty(), OpinionScope.GLOBAL);
        OpinionSignal macro = signal("MACRO", Optional.empty(), OpinionScope.MACRO);
        mockFiveSignals(btcLocal, ethLocal, paxgLocal, global, macro);

        User active1 = activeUser(1L, "active1");
        User active2 = activeUser(2L, "active2");
        // "archived"/"disabled" ne sont jamais retournés par ce mock (le filtrage réel est couvert
        // par UserRepositoryTest, pas par ce test) — présents ici seulement pour documenter
        // explicitement ce que le repository est censé exclure.
        User archived = User.builder().id(3L).username("archived").email("archived@test.com").password("hash")
                .roles(new HashSet<>()).enabled(true).lastLogin(NOW).archivedAt(NOW.minusSeconds(60)).build();
        User disabled = User.builder().id(4L).username("disabled").email("disabled@test.com").password("hash")
                .roles(new HashSet<>()).enabled(false).lastLogin(NOW).archivedAt(null).build();
        when(userRepository.findByEnabledTrueAndArchivedAtIsNull()).thenReturn(List.of(active1, active2));

        ScenarioEngine scenarioEngine = mock(ScenarioEngine.class);
        FixedDomainClock clock = new FixedDomainClock(NOW);
        DecisionOrchestrator orchestrator = newOrchestrator(scenarioEngine, new OwnerRefreshGuard(), clock);

        OrchestrationResult result = orchestrator.runCycle();

        assertEquals(5, result.signalsComputed());
        assertEquals(2, result.activeUsersFound());
        assertEquals(2, result.usersProcessed());
        assertEquals(0, result.usersSkippedLocked());

        verify(scenarioEngine, times(10)).onMarketOpinion(any(), any());

        ArgumentCaptor<ScenarioContext> contextCaptor = ArgumentCaptor.forClass(ScenarioContext.class);
        verify(scenarioEngine, times(10)).onMarketOpinion(any(), contextCaptor.capture());
        Set<ScenarioOwner> ownersSeen = new HashSet<>();
        contextCaptor.getAllValues().forEach(c -> ownersSeen.add(c.owner()));
        assertEquals(Set.of(ScenarioOwner.of(active1), ScenarioOwner.of(active2)), ownersSeen,
                "seuls les 2 owners actifs renvoyés par le repository doivent avoir été traités, jamais archived/disabled");
    }

    @Test
    @DisplayName("ScenarioContext.symbol() transmis correspond bien à signal.symbol() pour chaque signal (vide pour GLOBAL/MACRO)")
    void runCycle_propagatesCorrectSymbolPerSignal_toEachOwner() {
        OpinionSignal btcLocal = signal("BTC-LOCAL", Optional.of("BTC"), OpinionScope.LOCAL);
        OpinionSignal ethLocal = signal("ETH-LOCAL", Optional.of("ETH"), OpinionScope.LOCAL);
        OpinionSignal paxgLocal = signal("PAXG-LOCAL", Optional.of("PAXG"), OpinionScope.LOCAL);
        OpinionSignal global = signal("GLOBAL", Optional.empty(), OpinionScope.GLOBAL);
        OpinionSignal macro = signal("MACRO", Optional.empty(), OpinionScope.MACRO);
        mockFiveSignals(btcLocal, ethLocal, paxgLocal, global, macro);

        User active1 = activeUser(1L, "active1");
        when(userRepository.findByEnabledTrueAndArchivedAtIsNull()).thenReturn(List.of(active1));

        ScenarioEngine scenarioEngine = mock(ScenarioEngine.class);
        FixedDomainClock clock = new FixedDomainClock(NOW);
        DecisionOrchestrator orchestrator = newOrchestrator(scenarioEngine, new OwnerRefreshGuard(), clock);

        orchestrator.runCycle();

        ArgumentCaptor<OpinionSignal> signalCaptor = ArgumentCaptor.forClass(OpinionSignal.class);
        ArgumentCaptor<ScenarioContext> contextCaptor = ArgumentCaptor.forClass(ScenarioContext.class);
        verify(scenarioEngine, times(5)).onMarketOpinion(signalCaptor.capture(), contextCaptor.capture());

        List<OpinionSignal> capturedSignals = signalCaptor.getAllValues();
        List<ScenarioContext> capturedContexts = contextCaptor.getAllValues();
        for (int i = 0; i < capturedSignals.size(); i++) {
            assertEquals(capturedSignals.get(i).symbol(), capturedContexts.get(i).symbol(),
                    "le symbole du ScenarioContext doit toujours correspondre à celui du signal transmis");
        }
        // Vérifie explicitement le contraste LOCAL (renseigné) / GLOBAL-MACRO (vide) plutôt que de
        // se fier uniquement à l'égalité ci-dessus.
        long withSymbol = capturedContexts.stream().filter(c -> c.symbol().isPresent()).count();
        long withoutSymbol = capturedContexts.stream().filter(c -> c.symbol().isEmpty()).count();
        assertEquals(3, withSymbol, "les 3 signaux LOCAL doivent porter un symbole");
        assertEquals(2, withoutSymbol, "GLOBAL et MACRO ne doivent jamais porter de symbole");
    }

    @Test
    @DisplayName("Un owner déjà verrouillé (refresh en cours) est ignoré pour ce cycle (usersSkippedLocked)")
    void runCycle_skipsOwnerAlreadyLocked() {
        OpinionSignal btcLocal = signal("BTC-LOCAL", Optional.of("BTC"), OpinionScope.LOCAL);
        OpinionSignal ethLocal = signal("ETH-LOCAL", Optional.of("ETH"), OpinionScope.LOCAL);
        OpinionSignal paxgLocal = signal("PAXG-LOCAL", Optional.of("PAXG"), OpinionScope.LOCAL);
        OpinionSignal global = signal("GLOBAL", Optional.empty(), OpinionScope.GLOBAL);
        OpinionSignal macro = signal("MACRO", Optional.empty(), OpinionScope.MACRO);
        mockFiveSignals(btcLocal, ethLocal, paxgLocal, global, macro);

        User lockedUser = activeUser(1L, "locked");
        User freeUser = activeUser(2L, "free");
        when(userRepository.findByEnabledTrueAndArchivedAtIsNull()).thenReturn(List.of(lockedUser, freeUser));

        ScenarioEngine scenarioEngine = mock(ScenarioEngine.class);
        FixedDomainClock clock = new FixedDomainClock(NOW);
        OwnerRefreshGuard guard = new OwnerRefreshGuard();
        // Verrou déjà pris pour lockedUser juste avant le cycle (ex: refresh concurrent déjà en cours).
        assertTrue(guard.tryAcquire(ScenarioOwner.of(lockedUser), NOW));

        DecisionOrchestrator orchestrator = newOrchestrator(scenarioEngine, guard, clock);
        OrchestrationResult result = orchestrator.runCycle();

        assertEquals(2, result.activeUsersFound());
        assertEquals(1, result.usersProcessed());
        assertEquals(1, result.usersSkippedLocked());

        ArgumentCaptor<ScenarioContext> contextCaptor = ArgumentCaptor.forClass(ScenarioContext.class);
        verify(scenarioEngine, times(5)).onMarketOpinion(any(), contextCaptor.capture());
        assertFalse(contextCaptor.getAllValues().stream()
                        .anyMatch(c -> c.owner().equals(ScenarioOwner.of(lockedUser))),
                "onMarketOpinion ne doit jamais être appelé pour l'owner verrouillé sur ce cycle");
        assertTrue(contextCaptor.getAllValues().stream()
                        .allMatch(c -> c.owner().equals(ScenarioOwner.of(freeUser))),
                "seul l'owner libre doit avoir été traité");
    }

    @Test
    @DisplayName("Test d'intégration légère : un signal LOCAL BULLISH fort aboutit, via la cascade "
            + "existante (onMarketOpinion -> collectActionIntents -> ScenarioEvent -> "
            + "DecisionEngine.onScenarioEvent), à une Decision visible via getAllActiveDecisions() — "
            + "sans que l'orchestrateur n'appelle jamais DecisionEngine directement (décision 10)")
    void runCycle_strongBullishSignal_producesDecision_viaExistingCascade_notCallingDecisionEngineDirectly() {
        // Les 5 signaux du cycle partagent volontairement le même sens BULLISH fort : `observe()`
        // (DefaultMarketScenario) est appliqué par DefaultScenarioEngine à TOUS les scénarios actifs
        // de l'owner à chaque signal reçu, pas seulement au scénario de même symbole (vérifié en
        // lisant DefaultScenarioEngine.onMarketOpinion — la boucle `scenarios.forEach` n'y filtre pas
        // par symbole). Avec des signaux de sens différents (ex: BTC bullish + ETH/GLOBAL/MACRO
        // neutres), chaque signal neutre traité APRÈS le signal BTC réappliquerait NEUTRAL_DELTA
        // (-0.1) au scénario BTC déjà créé, faisant redescendre sa confidence au lieu de monter —
        // constaté empiriquement (échec de ce test avec des signaux neutres en bruit). Avec les 5
        // signaux alignés BULLISH, chaque signal renforce au contraire tous les scénarios déjà actifs
        // (même branche REINFORCE_DELTA), donc la confidence du scénario BTC monte à chaque signal
        // du cycle, pas seulement au signal BTC lui-même.
        OpinionSignal btcLocal = signal("BTC-LOCAL", Optional.of("BTC"), OpinionScope.LOCAL);
        OpinionSignal ethLocal = signal("ETH-LOCAL", Optional.of("ETH"), OpinionScope.LOCAL);
        OpinionSignal paxgLocal = signal("PAXG-LOCAL", Optional.of("PAXG"), OpinionScope.LOCAL);
        OpinionSignal global = signal("GLOBAL", Optional.empty(), OpinionScope.GLOBAL);
        OpinionSignal macro = signal("MACRO", Optional.empty(), OpinionScope.MACRO);
        mockFiveSignals(btcLocal, ethLocal, paxgLocal, global, macro);

        User active1 = activeUser(1L, "active1");
        when(userRepository.findByEnabledTrueAndArchivedAtIsNull()).thenReturn(List.of(active1));

        FixedDomainClock clock = new FixedDomainClock(NOW);
        EventBus eventBus = new EventBus();
        // Réel, jamais mocké : c'est justement l'objet de ce test (décision 10 du prompt — l'orchestrateur
        // n'appelle jamais DecisionEngine directement, seule la cascade existante doit y aboutir).
        DefaultScenarioEngine scenarioEngine = new DefaultScenarioEngine(clock, eventBus);
        DecisionEngine decisionEngine = new DecisionEngine(clock, eventBus, scenarioEngine);

        DecisionOrchestrator orchestrator = newOrchestrator(scenarioEngine, new OwnerRefreshGuard(), clock);

        // Tracé en détail (machine à états DefaultMarketScenario + boucle observe-tous-les-scénarios
        // de DefaultScenarioEngine, vérifiées en lisant le code, pas supposées) : dès le 1er cycle, le
        // scénario BTC (créé au signal BTC, confidence=0.95, EMERGING) est déjà renforcé par les
        // signaux ETH (+0.1 => CONFIRMING) puis PAXG (+0.1 => VALIDATED+stable) qui suivent dans le
        // même cycle -> Decision créée dès ce premier appel à runCycle(). Boucle de sécurité
        // (2 itérations max) conservée par prudence, pas parce que plusieurs cycles seraient attendus.
        boolean decisionFound = false;
        for (int cycle = 0; cycle < 2 && !decisionFound; cycle++) {
            orchestrator.runCycle();
            decisionFound = decisionEngine.getAllActiveDecisions().stream()
                    .anyMatch(d -> ScenarioOwner.of(active1).equals(d.getOwner()) && "BTC".equals(d.getSymbol()));
            // Avance au-delà du throttle OwnerRefreshGuard (1h) mais sous EXPIRATION_IDLE (2h) pour
            // ne jamais faire expirer les scénarios entre deux cycles, si une 2e itération était besoin.
            clock.advance(Duration.ofMinutes(61));
        }

        assertTrue(decisionFound,
                "un signal LOCAL BULLISH fort doit produire une Decision BTC via la cascade existante");

        // ETH/PAXG (mêmes signal/confidence, même renforcement croisé) produisent également leur
        // propre Decision dans ce scénario de test : ce n'est pas ce que ce test cherche à isoler
        // (seule la cascade côté BTC est vérifiée en détail), donc on ne contraint pas le total.
        Decision btcDecision = decisionEngine.getAllActiveDecisions().stream()
                .filter(d -> ScenarioOwner.of(active1).equals(d.getOwner()) && "BTC".equals(d.getSymbol()))
                .findFirst()
                .orElseThrow();
        assertEquals(DecisionType.ENTER, btcDecision.getSnapshot().type(), "signal BULLISH -> BUY -> ENTER");
    }
}
