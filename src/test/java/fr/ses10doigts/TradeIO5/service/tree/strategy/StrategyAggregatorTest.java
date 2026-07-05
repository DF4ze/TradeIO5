package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the conflict-detection bug in {@link StrategyAggregator aggregate}.
 * <p>
 * Bug: hasBuy/hasSell were assigned (=) instead of accumulated (|=) across the loop over
 * signals, so only the last signal in the list determined conflictDetected. This meant a
 * genuine BUY/SELL conflict between two non-last signals was silently dropped, contradicting
 * the class's documented intent ("Aucune stratégie ne doit avoir le dernier mot").
 */
@DisplayName("StrategyAggregator - conflict detection")
class StrategyAggregatorTest {

    @Test
    @DisplayName("should detect BUY/SELL conflict even when the conflicting signal is not last in the list")
    void should_detect_conflict_when_conflicting_signal_is_not_last() throws Exception {
        // tier used internally by StrategyAggregator.aggregate() is 2.0 / 3.0
        double tier = 2.0 / 3.0;

        // Signal 1: strong BUY (score above tier) -- the conflicting signal, NOT last in the list
        StrategySignal buySignal = StrategySignal.builder()
                .score(tier + 0.1)
                .valid(true)
                .strategyName("buyStrategy")
                .build();

        // Signal 2: strong SELL (score below -tier)
        StrategySignal sellSignal = StrategySignal.builder()
                .score(-(tier + 0.1))
                .valid(true)
                .strategyName("sellStrategy")
                .build();

        // Signal 3: neutral, placed last -- with the old buggy code (hasBuy = ..., hasSell = ...)
        // this last, neutral signal would overwrite both hasBuy and hasSell to false,
        // masking the earlier conflict.
        StrategySignal neutralSignal = StrategySignal.builder()
                .score(0.0)
                .valid(true)
                .strategyName("neutralStrategy")
                .build();

        List<StrategySignal> signals = List.of(buySignal, sellSignal, neutralSignal);

        AggregatedStrategySignal result = invokeAggregate(signals);

        assertTrue(result.isConflictDetected(),
                "conflictDetected should be true: a BUY/SELL conflict exists among the signals, " +
                "even though neither conflicting signal is last in the list");
    }

    /**
     * StrategyAggregator.aggregate(List<StrategySignal>) is a private static implementation
     * detail. It is invoked here via reflection to reproduce the exact regression scenario
     * (raw StrategySignal list) without going through the Strategy/MarketContext evaluation
     * pipeline, which is orthogonal to this bug.
     */
    private static AggregatedStrategySignal invokeAggregate(List<StrategySignal> signals) throws Exception {
        Method aggregate = StrategyAggregator.class.getDeclaredMethod("aggregate", List.class);
        aggregate.setAccessible(true);
        try {
            return (AggregatedStrategySignal) aggregate.invoke(null, signals);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
