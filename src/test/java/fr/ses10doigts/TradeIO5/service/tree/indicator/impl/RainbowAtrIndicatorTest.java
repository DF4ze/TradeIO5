package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RainbowAtrIndicator")
class RainbowAtrIndicatorTest {

    private final RainbowAtrIndicator indicator = new RainbowAtrIndicator();

    private static IndicatorContext context(int n, double close) {
        List<MarketData> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            data.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochSecond(86400L * i)).pair("BTCUSDT")
                    .open(BigDecimal.valueOf(close)).high(BigDecimal.valueOf(close + 1)).low(BigDecimal.valueOf(close - 1))
                    .close(BigDecimal.valueOf(close)).volume(BigDecimal.ONE).build());
        }
        MarketDataset ds = MarketDataset.builder().pair("BTCUSDT").timeFrame(TimeFrame.D1).marketDatas(data).size(n).build();
        return new IndicatorContext("BTC", TimeFrame.D1, ds, Map.of(), null);
    }

    private static IndicatorParameters params(Map<String, Double> numerics) {
        return IndicatorParameters.builder().indicatorType(IndicatorType.RAINBOW_ATR)
                .numerics(numerics).strings(Map.of()).booleans(Map.of()).build();
    }

    private static final Map<String, Double> BANDS = Map.of(
            "smaPeriod", 3.0, "atrPeriod", 3.0, "atrMultDown2", 2.0, "atrMultDown1", 1.0,
            "atrMultUp1", 1.0, "atrMultUp2", 2.0, "atrMultUp3", 3.0);

    @Test
    @DisplayName("Prix plat : SMA, bornes ATR et zone X1")
    void flatPriceBandsAndZone() {
        IndicatorResult r = indicator.compute(context(40, 100), params(BANDS));
        assertTrue(r.isValid());
        assertEquals(100.0, r.getValue(), 1e-9);
        assertEquals(2.0, r.getValues().get("atr"), 1e-9);
        assertEquals(96.0, r.getValues().get("extremeBas"), 1e-9);
        assertEquals(98.0, r.getValues().get("zoneBasse"), 1e-9);
        assertEquals(102.0, r.getValues().get("zoneHaute1"), 1e-9);
        assertEquals(104.0, r.getValues().get("zoneHaute2"), 1e-9);
        assertEquals(106.0, r.getValues().get("extremeHaut"), 1e-9);
        assertEquals(2.0, r.getValues().get("zone"), 1e-9); // X1
        assertEquals(0.0, r.getValues().get("sellArmed"), 1e-9);
    }

    @Test
    @DisplayName("Bornes ATR non monotones ou données insuffisantes => invalide")
    void invalidInputs() {
        Map<String, Double> bad = new java.util.HashMap<>(BANDS);
        bad.put("atrMultUp3", 1.0); // up3 < up2
        assertFalse(indicator.compute(context(40, 100), params(bad)).isValid());
        assertFalse(indicator.compute(context(3, 100), params(BANDS)).isValid());
    }

    @Test
    @DisplayName("Contrat : type, paramètres requis, taille de dataset")
    void contract() {
        assertEquals(IndicatorType.RAINBOW_ATR, indicator.getType());
        assertTrue(indicator.checkParameters(params(BANDS)));
        assertEquals(RainbowAtrIndicator.DEFAULT_LOOKBACK, indicator.getRequiredData(params(BANDS)));
    }
}
