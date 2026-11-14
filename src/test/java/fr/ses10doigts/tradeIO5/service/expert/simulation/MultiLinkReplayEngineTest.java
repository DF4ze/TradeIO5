package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rejeu multi-liens : cash et exposition communs")
class MultiLinkReplayEngineTest {

    private static final LocalDate START = LocalDate.parse("2024-06-10");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);

    private final List<MarketData> candles = SimulationFixtures.csv("BTC");
    private final RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
    private final RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
    private int startIdx;

    MultiLinkReplayEngineTest() throws IOException {
        while (candles.get(startIdx).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(START)) {
            startIdx++;
        }
    }

    private MultiLinkReplayEngine.Link link(long id, String asset, int priority) {
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TrendMixCalculator.Params.defaults(),
                RangeMapping.KEEP_PREVIOUS, sets, startIdx, startIdx, candles.size() - 1, null, Map.of(), Map.of(),
                java.util.Set.of(), 6);
        return new MultiLinkReplayEngine.Link(new LiveSlot(id, id, asset, 1L, priority, 100.0, null), cfg, 0.0);
    }

    private MultiLinkReplayEngine.Result run(double cash0, List<MultiLinkReplayEngine.Link> links) {
        return MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(links, cash0, ModulationSettings.neutral(), LIMITS,
                Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    @DisplayName("Cash commun : un seul achat couvert par jour, le lien suivant est bloqué ; l'ordre suit la priorité")
    void sharedCashBlocksLaterLink() {
        // cash juste suffisant pour ~un achat par jour : le lien de priorité 1 passe, celui de priorité 2 est bloqué
        MultiLinkReplayEngine.Result r = run(2.5, List.of(link(2, "ETH", 2), link(1, "BTC", 1)));
        assertTrue(r.linkDays().stream().anyMatch(d -> d.asset().equals("ETH") && d.blockReason() == LiveBlockReason.INSUFFICIENT_CASH));
        for (MultiLinkReplayEngine.LinkDay btc : r.linkDays().stream().filter(d -> d.asset().equals("BTC")).toList()) {
            MultiLinkReplayEngine.LinkDay eth = r.linkDays().stream()
                    .filter(d -> d.asset().equals("ETH") && d.day().equals(btc.day())).findFirst().orElseThrow();
            if (btc.action() == RainbowLiveAction.BLOCKED) {
                assertEquals(RainbowLiveAction.BLOCKED == eth.action() || eth.action() == RainbowLiveAction.NONE
                        || eth.action() == RainbowLiveAction.SELL, true, "le lien servi après ne peut pas acheter");
            }
        }
        assertTrue(r.walletDays().stream().allMatch(w -> w.cash() >= -1e-9));
    }

    @Test
    @DisplayName("Cash illimité : chaque lien se comporte comme un rejeu mono-lien")
    void unlimitedCashEqualsMono() {
        MultiLinkReplayEngine.Result r = run(1e12, List.of(link(1, "BTC", 1), link(2, "ETH", 2)));
        FullReplayEngine.Result mono = FullReplayEngine.run(new FullReplayEngine.Input(ds, candles,
                TrendMixCalculator.Params.defaults(), RangeMapping.KEEP_PREVIOUS, sets, startIdx, startIdx, candles.size() - 1, 1e12,
                0.0, null, 100.0, ModulationSettings.neutral(), LIMITS, Map.of(), Map.of(), Map.of(), Map.of(), java.util.Set.of(), 6));
        List<MultiLinkReplayEngine.LinkDay> btc = r.linkDays().stream().filter(d -> d.asset().equals("BTC")).toList();
        assertEquals(mono.days().size(), btc.size());
        for (int i = 0; i < btc.size(); i++) {
            assertEquals(mono.days().get(i).action(), btc.get(i).action(), "jour " + i);
            assertEquals(mono.days().get(i).amountUsdc(), btc.get(i).amountUsdc(), "jour " + i);
        }
        assertFalse(r.linkDays().isEmpty());
    }

    @Test
    @DisplayName("Wallet recalé : cash, quantités et cash déjà réservé imposés")
    void pinnedWallet() {
        MultiLinkReplayEngine.Result free = run(1000.0, List.of(link(1, "BTC", 1), link(2, "ETH", 2)));
        LocalDate day = free.walletDays().get(free.walletDays().size() / 2).day();
        MultiLinkReplayEngine.Result pinned = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(
                List.of(link(1, "BTC", 1), link(2, "ETH", 2)), 1000.0, ModulationSettings.neutral(), LIMITS, Map.of(),
                Map.of(day, new MultiLinkReplayEngine.Pin(42.0, Map.of("BTC", 1.5, "ETH", 3.0), Map.of(2L, 10.0))), Map.of(), Map.of()));
        MultiLinkReplayEngine.LinkDay eth = pinned.linkDays().stream()
                .filter(d -> d.asset().equals("ETH") && d.day().equals(day)).findFirst().orElseThrow();
        assertEquals(42.0, eth.cashBefore(), 1e-9);
        assertEquals(3.0, eth.qtyBefore(), 1e-9);
        assertTrue(eth.cashReservedBefore() >= 10.0 - 1e-9);
    }

    @Test
    @DisplayName("Avec coûts : cash débité = ce qui est exécuté, quantité nette de frais, frais cumulés ; DCA fixe avec les mêmes frais")
    void withCosts() {
        java.util.Map<String, OrderReplay.Quote> quotes = java.util.Map.of("BTC-USDC",
                new OrderReplay.Quote(new java.math.BigDecimal("0.5"), new java.math.BigDecimal("0.05"), java.math.BigDecimal.ZERO));
        OrderReplay orders = new OrderReplay(new OrderReplay.Schedule(java.util.List.of(new fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo(
                "BTC-USDC", "BTC", "USDC", new java.math.BigDecimal("0.00001"), new java.math.BigDecimal("0.00001"), new java.math.BigDecimal("0.01"))),
                java.util.Set.of("USDC"), java.util.Set.of("EUR", "USDT"), quotes, Map.of(), java.util.List.of()),
                new fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest());
        MultiLinkReplayEngine.Costs costs = new MultiLinkReplayEngine.Costs(orders, Map.of(1L, 1e9), Map.of());
        MultiLinkReplayEngine.Result free = run(1e9, List.of(link(1, "BTC", 1)));
        MultiLinkReplayEngine.Result paid = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(link(1, "BTC", 1)), 1e9,
                ModulationSettings.neutral(), LIMITS, Map.of(), Map.of(), Map.of(), Map.of(), costs));
        // minimum énorme : tout achat est reporté (BELOW_MIN), carry cumulé, aucun débit ni quantité
        assertTrue(paid.costDays().stream().allMatch(c -> !c.outcome().equals("OK") || c.cashDelta() > 0));
        assertEquals(1e9, paid.walletDays().getLast().cash(), 1e-6);
        assertEquals(0.0, paid.walletDays().getLast().qty().getOrDefault("BTC", 0.0), 1e-12);
        assertTrue(paid.costDays().stream().anyMatch(c -> c.outcome().equals("BELOW_MIN") && c.carryOut() > 0));
        MultiLinkReplayEngine.Costs fees = new MultiLinkReplayEngine.Costs(orders, Map.of(), Map.of());
        MultiLinkReplayEngine.Result withFees = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(link(1, "BTC", 1)), 1e9,
                ModulationSettings.neutral(), LIMITS, Map.of(), Map.of(), Map.of(), Map.of(), fees));
        assertTrue(withFees.totalCostUsdc() > 0);
        assertTrue(withFees.fixedTotalCostUsdc() > 0);
        assertTrue(withFees.walletDays().getLast().qty().get("BTC") < free.walletDays().getLast().qty().get("BTC") + 1e-12,
                "quantité nette de frais <= quantité sans frais");
        assertEquals(0.0, free.totalCostUsdc());
        assertTrue(withFees.costDays().stream().filter(c -> c.outcome().equals("OK")).allMatch(c -> c.costPct() > 0.5));
    }
}
