package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("FeeTest : GREEN < 0,2 ≤ WARNING ≤ 0,8 < RED")
class FeeTestTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("Bornes par défaut : 0,19 GREEN ; 0,2 et 0,8 WARNING ; 0,81 RED")
    void defaultBounds() {
        FeeTest feeTest = new FeeTest();
        assertEquals(FeeTestLevel.GREEN, feeTest.level(d("0")));
        assertEquals(FeeTestLevel.GREEN, feeTest.level(d("0.19")));
        assertEquals(FeeTestLevel.WARNING, feeTest.level(d("0.2")));
        assertEquals(FeeTestLevel.WARNING, feeTest.level(d("0.200000")));
        assertEquals(FeeTestLevel.WARNING, feeTest.level(d("0.5")));
        assertEquals(FeeTestLevel.WARNING, feeTest.level(d("0.8")));
        assertEquals(FeeTestLevel.WARNING, feeTest.level(d("0.80")));
        assertEquals(FeeTestLevel.RED, feeTest.level(d("0.81")));
        assertEquals(FeeTestLevel.RED, feeTest.level(d("12")));
    }

    @Test
    @DisplayName("Un rebate (coût négatif) est GREEN")
    void negativeCost() {
        assertEquals(FeeTestLevel.GREEN, new FeeTest().level(d("-0.05")));
    }

    @Test
    @DisplayName("Seuils configurables")
    void configurable() {
        FeeTest feeTest = new FeeTest(d("0.1"), d("0.3"));
        assertEquals(FeeTestLevel.GREEN, feeTest.level(d("0.09")));
        assertEquals(FeeTestLevel.WARNING, feeTest.level(d("0.1")));
        assertEquals(FeeTestLevel.WARNING, feeTest.level(d("0.3")));
        assertEquals(FeeTestLevel.RED, feeTest.level(d("0.31")));
    }

    @Test
    @DisplayName("Seuils incohérents refusés")
    void invalidThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new FeeTest(d("0.9"), d("0.8")));
        assertThrows(IllegalArgumentException.class, () -> new FeeTest(d("-0.1"), d("0.8")));
    }
}
