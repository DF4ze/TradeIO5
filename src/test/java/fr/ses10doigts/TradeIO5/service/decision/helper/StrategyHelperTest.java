package fr.ses10doigts.TradeIO5.service.decision.helper;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.impl.RsiStrategySignalTypeWithConfidence;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.decision.helper.StrategyHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyHelperTest {

    @Test
    void evaluateRsiSinalWithConfidence() {
        // Hold
        RsiStrategySignalTypeWithConfidence evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(50, 30, 70);
        assertEquals(SignalType.HOLD, evaluated.getSignal());
        assertEquals(1, evaluated.getConfidence());

        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(31, 30, 70);
        assertEquals(SignalType.HOLD, evaluated.getSignal());
        assertTrue(evaluated.getConfidence() >= StrategyHelper.MIN_CONFIDENCE );

        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(69, 30, 70);
        assertEquals(SignalType.HOLD, evaluated.getSignal());
        assertTrue(evaluated.getConfidence() >= StrategyHelper.MIN_CONFIDENCE );

        // Buy
        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(0, 30, 70);
        assertEquals(SignalType.BUY, evaluated.getSignal());
        assertEquals(1, evaluated.getConfidence());

        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(29, 30, 70);
        assertEquals(SignalType.BUY, evaluated.getSignal());
        assertTrue(evaluated.getConfidence() >= StrategyHelper.MIN_CONFIDENCE );

        // Sell
        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(100, 30, 70);
        assertEquals(SignalType.SELL, evaluated.getSignal());
        assertEquals(1, evaluated.getConfidence());

        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(71, 30, 70);
        assertEquals(SignalType.SELL, evaluated.getSignal());
        assertTrue(evaluated.getConfidence() >= StrategyHelper.MIN_CONFIDENCE );

        // Limit
        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(70, 30, 70);
        assertEquals(SignalType.SELL, evaluated.getSignal());
        assertEquals(StrategyHelper.MIN_CONFIDENCE, evaluated.getConfidence());

        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(30, 30, 70);
        assertEquals(SignalType.BUY, evaluated.getSignal());
        assertEquals(StrategyHelper.MIN_CONFIDENCE, evaluated.getConfidence());

        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(110, 30, 70);
        assertEquals(SignalType.SELL, evaluated.getSignal());
        assertEquals(1, evaluated.getConfidence());

        evaluated = StrategyHelper.evaluateRsiSinalWithConfidence(-10, 30, 70);
        assertEquals(SignalType.BUY, evaluated.getSignal());
        assertEquals(1, evaluated.getConfidence());
    }
}