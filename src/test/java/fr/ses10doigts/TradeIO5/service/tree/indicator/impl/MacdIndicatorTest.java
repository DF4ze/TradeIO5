package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("Indicator - MACD")
class MacdIndicatorTest {

    @Autowired
    IndicatorEngine indicatorEngine;

    static IndicatorParameters macdParams;

    private static DomainClock clock;

    @BeforeAll
    static void init() {
        macdParams = macdParams(5, 7);

        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @Test
    @DisplayName("MACD - tendance haussière")
    void testMacdUptrend() {
        IndicatorContext context = context(List.of(
                bd(10), bd(11), bd(12), bd(13), bd(14), bd(15), bd(16)
        ), clock);

        IndicatorSnapshot macd = indicatorEngine.execute(context, macdParams);
        assertTrue(macd.getResult().isValid(), "MACD doit être valide");
        assertNotNull(macd.getResult().getValue(), "MACD ne doit pas être null");
        assertTrue(macd.getResult().getValue() > 0, "MACD doit être positif");
        System.out.println("MACD Uptrend: " + macd.getResult().getValue());
    }

    @Test
    @DisplayName("MACD - tendance baissière")
    void testMacdDowntrend() {
        IndicatorContext context = context(List.of(
                bd(16), bd(15), bd(14), bd(13), bd(12), bd(11), bd(10)
        ), clock);

        IndicatorSnapshot macd = indicatorEngine.execute(context, macdParams);
        assertTrue(macd.getResult().isValid(), "MACD doit être valide");
        assertNotNull(macd.getResult().getValue(), "MACD ne doit pas être null");
        assertTrue(macd.getResult().getValue() < 0, "MACD doit être négatif");
        System.out.println("MACD Downtrend: " + macd.getResult().getValue());
    }

    @Test
    @DisplayName("MACD - tendance plate")
    void testMacdFlat() {
        IndicatorContext context = context(List.of(
                bd(10), bd(10), bd(10), bd(10), bd(10), bd(10), bd(10)
        ), clock);

        IndicatorSnapshot macd = indicatorEngine.execute(context, macdParams);
        assertTrue(macd.getResult().isValid(), "MACD doit être valide");
        assertNotNull(macd.getResult().getValue(), "MACD ne doit pas être null");
        assertEquals(0, (double) macd.getResult().getValue(), "MACD doit être à 0");
        System.out.println("MACD Flat: " + macd.getResult().getValue());
    }
}