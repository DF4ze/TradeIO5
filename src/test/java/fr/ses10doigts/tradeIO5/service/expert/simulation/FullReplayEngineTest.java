package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rejeu complet mono-actif")
class FullReplayEngineTest {

    private static final LocalDate START = LocalDate.parse("2024-06-10");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);

    private final List<MarketData> candles = SimulationFixtures.csv("BTC");
    private final RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
    private final TrendMixCalculator.Params trend = TrendMixCalculator.Params.defaults();
    private final RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
    private int startIdx;

    FullReplayEngineTest() throws IOException {
        startIdx = 0;
        while (candles.get(startIdx).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(START)) {
            startIdx++;
        }
    }

    private FullReplayEngine.Result run(double cash0, double qty0, Map<LocalDate, double[]> pinned) {
        return FullReplayEngine.run(new FullReplayEngine.Input(ds, candles, trend, RangeMapping.KEEP_PREVIOUS, sets, startIdx,
                startIdx, candles.size() - 1, cash0, qty0, null, 100.0, ModulationSettings.neutral(), LIMITS, Map.of(), pinned, Map.of(), Map.of(), java.util.Set.of(), 6));
    }

    @Test
    @DisplayName("Cash illimité, réglages neutres : mêmes achats et ventes que le rejeu du signal")
    void unlimitedCashMatchesSignalReplay() {
        TrendRegime[] regime = TrendMixCalculator.compute(candles, ds.sma(trend.smaPeriod()), ds.atr(trend.atrPeriod()), trend).regime();
        List<DayRow> expected = RainbowAtrReplay.run(ds, sets, RainbowSetSelector.select(regime, RangeMapping.KEEP_PREVIOUS),
                startIdx, candles.size() - 1);
        FullReplayEngine.Result result = run(1e12, 0.0, Map.of());

        assertEquals(expected.size(), result.days().size());
        int trades = 0;
        for (int i = 0; i < expected.size(); i++) {
            DayRow row = expected.get(i);
            FullReplayEngine.Day day = result.days().get(i);
            if (row.sellQuantity() > 1e-9) {
                assertEquals(RainbowLiveAction.SELL, day.action(), "jour " + i);
                assertEquals(row.sellQuantity(), day.quantity(), 1e-6 * Math.max(1, row.sellQuantity()), "jour " + i);
                trades++;
            } else if (row.buyAmount() > 1e-9) {
                assertEquals(RainbowLiveAction.BUY, day.action(), "jour " + i);
                assertEquals(row.buyAmount(), day.amountUsdc(), 1e-6 * row.buyAmount(), "jour " + i);
                trades++;
            } else {
                assertEquals(RainbowLiveAction.NONE, day.action(), "jour " + i);
            }
        }
        assertTrue(trades > 0);
    }

    @Test
    @DisplayName("Capital fixé : cash épuisé ⇒ achats bloqués, cash jamais négatif")
    void exhaustedCashBlocksBuys() {
        FullReplayEngine.Result result = run(1.0, 0.0, Map.of());
        assertTrue(result.days().stream().anyMatch(d -> d.blockReason() == LiveBlockReason.INSUFFICIENT_CASH));
        assertTrue(result.days().stream().allMatch(d -> d.cashAfter() >= -1e-9));
        assertFalse(result.days().stream().anyMatch(d -> d.action() == RainbowLiveAction.BLOCKED && d.cashAfter() != d.cashBefore()));
    }

    @Test
    @DisplayName("Wallet recalé : le cash et la quantité du jour sont ceux imposés")
    void pinnedWalletOverridesState() {
        FullReplayEngine.Result free = run(1000.0, 0.0, Map.of());
        LocalDate day = free.days().get(free.days().size() / 2).day();
        FullReplayEngine.Result pinned = run(1000.0, 0.0, Map.of(day, new double[]{42.0, 1.5}));
        FullReplayEngine.Day d = pinned.days().stream().filter(x -> x.day().equals(day)).findFirst().orElseThrow();
        assertEquals(42.0, d.cashBefore(), 1e-9);
        assertEquals(1.5, d.qtyBefore(), 1e-9);
    }
}
