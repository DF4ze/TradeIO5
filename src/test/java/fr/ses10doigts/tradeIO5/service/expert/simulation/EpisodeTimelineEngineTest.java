package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.EpisodeTimeline.AssetSeries;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Timeline des épisodes sur le rejeu complet : invariants PnL ↔ wallet et gain ↔ DCA fixe")
class EpisodeTimelineEngineTest {

    private static final LocalDate START = LocalDate.parse("2024-06-10");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);

    private final List<MarketData> candles = SimulationFixtures.csv("BTC");
    private final RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
    private final RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
    private int startIdx;

    EpisodeTimelineEngineTest() throws IOException {
        while (candles.get(startIdx).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(START)) {
            startIdx++;
        }
    }

    private MultiLinkReplayEngine.Link link(long id, String asset, int priority, double qty0) {
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TrendMixCalculator.Params.defaults(),
                RangeMapping.KEEP_PREVIOUS, sets, startIdx, startIdx, candles.size() - 1, null, Map.of(), Map.of(),
                java.util.Set.of(), 6);
        return new MultiLinkReplayEngine.Link(new LiveSlot(id, id, asset, 1L, priority, 100.0, null), cfg, qty0);
    }

    private static List<AssetSeries> series(MultiLinkReplayEngine.Result r, List<MultiLinkReplayEngine.Link> links) {
        List<AssetSeries> out = new ArrayList<>();
        for (MultiLinkReplayEngine.Link l : links) {
            out.add(new AssetSeries(l.slot().assetSymbol(), r.trades().stream()
                    .filter(t -> t.bindingId().equals(l.slot().bindingId())).toList(), l.qty0(), Map.of()));
        }
        return out;
    }

    @Test
    @DisplayName("Mono-lien : Σ PnL = valeur finale − valeur initiale ; Σ gain = valeur stratégie − valeur DCA fixe")
    void invariantsMono() {
        for (double qty0 : new double[]{0.0, 0.05}) {
            List<MultiLinkReplayEngine.Link> links = List.of(link(1, "BTC", 1, qty0));
            MultiLinkReplayEngine.Result r = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(links, 5000,
                    ModulationSettings.neutral(), LIMITS, Map.of(), Map.of(), Map.of(), Map.of()));
            assertFalse(r.trades().isEmpty());
            EpisodeTimeline.Report rep = EpisodeTimeline.compute(series(r, links), null, null);
            double p0 = r.trades().getFirst().price();
            assertEquals(r.finalValue() - (5000 + qty0 * p0), rep.pnlTotal(), 1e-6);
            assertEquals(r.finalValue() - r.fixedFinalValue(), rep.gain().total().total(), 1e-6);
            assertFalse(rep.episodes().isEmpty());
        }
    }

    @Test
    @DisplayName("Multi-liens (cash commun) : mêmes invariants, somme des actifs")
    void invariantsMulti() {
        List<MultiLinkReplayEngine.Link> links = List.of(link(1, "BTC", 1, 0.0), link(2, "ETH", 2, 0.0));
        MultiLinkReplayEngine.Result r = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(links, 3000,
                ModulationSettings.neutral(), LIMITS, Map.of(), Map.of(), Map.of(), Map.of()));
        EpisodeTimeline.Report rep = EpisodeTimeline.compute(series(r, links), null, null);
        assertEquals(r.finalValue() - 3000, rep.pnlTotal(), 1e-6);
        assertEquals(r.finalValue() - r.fixedFinalValue(), rep.gain().total().total(), 1e-6);
        assertEquals(2, rep.gain().assets().size());
    }
}
