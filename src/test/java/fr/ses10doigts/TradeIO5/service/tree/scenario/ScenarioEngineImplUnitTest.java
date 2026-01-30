package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioFactory;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import fr.ses10doigts.tradeIO5.service.tree.scenario.history.ScenarioHistoryLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScenarioEngineImplUnitTest {

    @Mock
    ScenarioHistoryLogger historyLogger;

    @Mock
    ScenarioFactory scenarioFactory;

    @Mock
    MarketScenario existingScenario;

    @Mock
    MarketScenario createdScenario;

    @InjectMocks
    ScenarioEngineImpl engine;

    private final ScenarioOwner owner = ScenarioOwner.user("user1");
    private final ScenarioOwner otherOwner = ScenarioOwner.user("user2");

    private ScenarioContext context;
    private ExecutionMode executionMode;
    private FixedDomainClock clock;

    @BeforeEach
    void setUp() {
        executionMode = ExecutionMode.BACKTEST;

        clock = new FixedDomainClock(Instant.parse("2024-01-01T00:00:00Z"));
        context = new ScenarioContext(
                owner,
                Optional.of("BTC"),
                clock,
                clock.now(),
                List.of()
        );
    }

    @Test
    void shouldObserveExistingScenarioAndHistorizeIfStateChanged() {
        ScenarioState before = mock(ScenarioState.class);
        ScenarioState after = mock(ScenarioState.class);


        when(existingScenario.getState()).thenReturn(before, after);

        ScenarioKey key = new ScenarioKey(
                owner,
                ScenarioType.TREND_UP,
                Optional.of("BTC")
        );
        engine.scenarios.put(key, existingScenario);

        engine.onMarketOpinion(
                mock(MarketOpinionResult.class),
                context,
                executionMode
        );

        verify(historyLogger, atLeastOnce()).logChange(any(), eq(executionMode));
    }

    @Test
    void shouldCreateNewScenarioFromFactory() {
        when(createdScenario.getOwner()).thenReturn(owner);
        when(createdScenario.getType()).thenReturn(ScenarioType.RANGE);
        when(createdScenario.getSymbol()).thenReturn(Optional.empty());
        when(createdScenario.isActive(any(), any())).thenReturn(true);

        when(scenarioFactory.create(any(), any()))
                .thenReturn(List.of(createdScenario));


        engine.onMarketOpinion(
                mock(MarketOpinionResult.class),
                context,
                executionMode
        );

        List<MarketScenario> active = engine.getActiveScenarios(
                owner,
                Instant.now(),
                Duration.ofDays(1)
        );

        assertEquals(1, active.size());
        assertTrue(active.contains(createdScenario));
        verify(historyLogger, atLeastOnce()).logChange(any(), eq(executionMode));
    }

    @Test
    void shouldMergeScenarioIfSameKeyExists() {
        ScenarioState after = mock(ScenarioState.class);

        when(existingScenario.getState())
                .thenReturn(new ScenarioState(ScenarioType.TREND_UP, clock.now()), after);

        when(createdScenario.getOwner()).thenReturn(owner);
        when(createdScenario.getType()).thenReturn(ScenarioType.TREND_UP);
        when(createdScenario.getSymbol()).thenReturn(Optional.of("BTC"));

        when(scenarioFactory.create(any(), any()))
                .thenReturn(List.of(createdScenario));

        ScenarioKey key = new ScenarioKey(
                owner,
                ScenarioType.TREND_UP,
                Optional.of("BTC")
        );

        engine.scenarios.put(key, existingScenario);

        // 1er passage → création
        engine.onMarketOpinion(
                mock(MarketOpinionResult.class),
                context,
                executionMode
        );

        // 2e passage → merge
        engine.onMarketOpinion(
                mock(MarketOpinionResult.class),
                context,
                executionMode
        );

        verify(existingScenario, atLeastOnce()).enrichFrom(createdScenario);
        verify(historyLogger, atLeastOnce()).logChange(any(), eq(executionMode));
    }

    @Test
    void shouldNotExposeOtherUserScenario() {
        ScenarioKey key = new ScenarioKey(
                otherOwner,
                ScenarioType.CRASH,
                Optional.empty()
        );

        engine.scenarios.put(key, existingScenario);

        List<MarketScenario> visible = engine.getActiveScenarios(
                owner,
                Instant.now(),
                Duration.ofDays(1)
        );

        assertTrue(visible.isEmpty());
    }

    @Test
    void shouldCleanupInactiveScenarioAndHistorize() {
        when(existingScenario.isActive(any(), any())).thenReturn(false);

        ScenarioKey key = new ScenarioKey(owner, ScenarioType.TREND_UP, Optional.of("BTC"));
        engine.scenarios.put(key, existingScenario);
        engine.cleanup(
                Instant.now(),
                Duration.ofDays(1),
                executionMode
        );

        verify(historyLogger, atLeastOnce()).logChange(any(), eq(executionMode));
    }

}