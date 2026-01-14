package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FearAndGreedIndicatorTest {

    @Test
    void compute() {
        FearAndGreedIndicator indicator = new FearAndGreedIndicator();

        IndicatorResult result = indicator.compute(null, null);

        System.out.println("Fear and Greed : "+result.getValue());

        assertTrue(result.isValid());
        assertNotNull(result.getValue());
    }
}