package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ChartDto;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Graphique Rainbow d'un lien du rejeu libre")
class AssetChartBuilderTest {

    private static final LocalDate START = LocalDate.parse("2024-06-10");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);

    private final List<MarketData> candles = SimulationFixtures.csv("BTC");
    private final RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
    private final RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
    private int startIdx;

    AssetChartBuilderTest() throws IOException {
        while (candles.get(startIdx).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(START)) {
            startIdx++;
        }
    }

    private MultiLinkReplayEngine.Result run(double cash0) {
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TrendMixCalculator.Params.defaults(),
                RangeMapping.KEEP_PREVIOUS, sets, startIdx, startIdx, candles.size() - 1, null, Map.of(), Map.of(), Set.of(), 6);
        MultiLinkReplayEngine.Link link = new MultiLinkReplayEngine.Link(new LiveSlot(1L, 1L, "BTC", 1L, 1, 100.0, null), cfg, 0.0);
        return MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(link), cash0, ModulationSettings.neutral(), LIMITS,
                Map.of(), Map.of(), Map.of(), Map.of()));
    }

    private static double sum(List<Number> l) {
        return l.stream().filter(x -> x != null).mapToDouble(Number::doubleValue).sum();
    }

    @Test
    @DisplayName("Toutes les colonnes ont la longueur des jours du lien ; wallet aligné")
    void columnsAligned() {
        MultiLinkReplayEngine.Result r = run(100_000);
        ChartDto c = AssetChartBuilder.build(1L, "BTC", ds, r, 100_000, 0.0);
        assertNotNull(c);
        int n = c.d().size();
        assertEquals(r.linkDays().size(), n);
        assertEquals(n, c.trend().size());
        assertEquals(n, c.active().size());
        c.cols().forEach((k, v) -> assertEquals(n, v.size(), "colonne " + k));
        assertEquals(n, c.wallet().total().size());
        assertEquals(100_000, c.wallet().capital());
    }

    @Test
    @DisplayName("Achats : montant réellement débité ; PnL total final = valeur finale de la position + cash échangé")
    void buysAndPnlMatchTrades() {
        MultiLinkReplayEngine.Result r = run(100_000);
        ChartDto c = AssetChartBuilder.build(1L, "BTC", ds, r, 100_000, 0.0);
        double cashOut = r.trades().stream().filter(t -> t.qtyDelta() > 0).mapToDouble(t -> -t.cashDelta()).sum();
        assertEquals(cashOut, sum(c.cols().get("buyAmt")), 1e-6);
        assertTrue(cashOut > 0, "le jeu d'essai doit contenir des achats");
        int last = c.d().size() - 1;
        double qty = r.trades().stream().mapToDouble(t -> t.qtyDelta()).sum();
        double cash = r.trades().stream().mapToDouble(t -> t.cashDelta()).sum();
        double lastPrice = r.trades().getLast().price();
        assertEquals(qty * lastPrice + cash, c.cols().get("total").get(last).doubleValue(), 1e-6);
        assertEquals(c.cols().get("total").get(last).doubleValue(),
                c.cols().get("realized").get(last).doubleValue() + c.cols().get("potential").get(last).doubleValue(), 1e-6);
    }

    @Test
    @DisplayName("Cash nul : les ordres voulus non passés sont marqués « bloqué » avec leur montant voulu")
    void blockedOrdersAreMarked() {
        MultiLinkReplayEngine.Result r = run(0);
        ChartDto c = AssetChartBuilder.build(1L, "BTC", ds, r, 0, 0.0);
        long wantedNotDone = r.linkDays().stream()
                .filter(x -> x.wantedAction() != RainbowLiveAction.NONE && x.action() != RainbowLiveAction.BUY && x.action() != RainbowLiveAction.SELL)
                .count();
        long blocked = c.cols().get("blocked").stream().filter(x -> x != null && x.intValue() > 0).count();
        assertTrue(wantedNotDone > 0, "un wallet sans cash doit bloquer des achats");
        assertEquals(wantedNotDone, blocked);
        assertTrue(sum(c.cols().get("blockedAmt")) > 0);
    }

    @Test
    @DisplayName("Bornes ATR présentes dès que SMA / ATR sont définis, ordonnées down2 < down1 < up3")
    void bandsPresent() {
        ChartDto c = AssetChartBuilder.build(1L, "BTC", ds, run(100_000), 100_000, 0.0);
        int last = c.d().size() - 1;
        double d2 = c.cols().get("down2").get(last).doubleValue(), d1 = c.cols().get("down1").get(last).doubleValue(),
                u3 = c.cols().get("up3").get(last).doubleValue();
        assertTrue(d2 < d1 && d1 < u3);
        assertTrue(c.cols().get("ath").get(last).doubleValue() > 0);
    }
}
