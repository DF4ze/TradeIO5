package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicySpec;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.DayState;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicies;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicy;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Hook de politique du rejeu : parité, absence de look-ahead, bornes")
class ModulationPolicyReplayTest {

    private static final LocalDate START = LocalDate.parse("2024-06-10");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);
    private static final double EPS = 1e-9;

    private final List<MarketData> candles = SimulationFixtures.csv("BTC");
    private final RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
    private final RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
    private int startIdx;

    ModulationPolicyReplayTest() throws IOException {
        while (candles.get(startIdx).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(START)) {
            startIdx++;
        }
    }

    private MultiLinkReplayEngine.Result run(int endIdx, ModulationPolicy policy) {
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TrendMixCalculator.Params.defaults(),
                RangeMapping.KEEP_PREVIOUS, sets, startIdx, startIdx, endIdx, null, Map.of(), Map.of(), java.util.Set.of(), 6);
        MultiLinkReplayEngine.Link link = new MultiLinkReplayEngine.Link(new LiveSlot(1L, 1L, "BTC", 1L, 1, 100.0, null), cfg, 0.0);
        return MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(link), 1000.0, ModulationSettings.neutral(), LIMITS,
                Map.of(), Map.of(), Map.of(), Map.of(), null, policy));
    }

    @Test
    @DisplayName("Parité : policy null == CONSTANT, champ par champ")
    void parityNullVersusConstant() {
        MultiLinkReplayEngine.Result none = run(candles.size() - 1, null);
        MultiLinkReplayEngine.Result constant = run(candles.size() - 1, ModulationPolicies.from(new PolicySpec("CONSTANT", Map.of())));
        assertFalse(none.walletDays().isEmpty());
        assertTrue(none.policyDays().isEmpty());
        assertEquals(none.walletDays().size(), constant.policyDays().size());
        assertEquals(none.linkDays(), constant.linkDays());
        assertEquals(none.walletDays(), constant.walletDays());
        assertEquals(none.trades(), constant.trades());
        assertEquals(none.costDays(), constant.costDays());
        assertEquals(none.signals(), constant.signals());
        assertEquals(none.qty0(), constant.qty0());
        assertEquals(none.cash0(), constant.cash0(), 0.0);
        assertEquals(none.finalValue(), constant.finalValue(), 0.0);
        assertEquals(none.fixedFinalValue(), constant.fixedFinalValue(), 0.0);
        assertEquals(none.totalCostUsdc(), constant.totalCostUsdc(), 0.0);
        assertEquals(none.fixedTotalCostUsdc(), constant.fixedTotalCostUsdc(), 0.0);
        assertTrue(constant.policyDays().stream().allMatch(d -> d.applied().equals(ModulationSettings.neutral())));
    }

    @Test
    @DisplayName("Pas de look-ahead : plus haut = fins de journée < J ; états identiques si la plage est tronquée")
    void noLookAhead() {
        List<DayState> full = new ArrayList<>();
        MultiLinkReplayEngine.Result r = run(candles.size() - 1, (state, base) -> {
            full.add(state);
            return base;
        });
        assertEquals(r.walletDays().size(), full.size());
        double peak = 1000.0;
        for (int i = 0; i < full.size(); i++) {
            DayState s = full.get(i);
            assertEquals(r.walletDays().get(i).day(), s.day());
            assertEquals(peak, s.peakValue(), EPS, "plus haut du jour " + s.day());
            assertEquals(Math.min(0.0, 100.0 * (s.totalValue() / peak - 1.0)), s.drawdownPct(), EPS);
            assertTrue(s.drawdownPct() <= 0.0);
            peak = Math.max(peak, r.walletDays().get(i).totalValue());
        }
        int cut = full.size() / 2;
        List<DayState> truncated = new ArrayList<>();
        run(startIdx + cut - 1, (state, base) -> {
            truncated.add(state);
            return base;
        });
        assertEquals(cut, truncated.size());
        assertEquals(full.subList(0, cut), truncated);
        assertEquals(1000.0, full.getFirst().peakValue(), EPS);
        assertEquals(1000.0, full.getFirst().cash(), EPS);
    }

    @Test
    @DisplayName("daysInRegime : 1 à la bascule, +1 par jour consécutif dans le même régime")
    void daysInRegime() {
        List<DayState> states = new ArrayList<>();
        run(candles.size() - 1, (state, base) -> {
            states.add(state);
            return base;
        });
        Map<String, String> lastRegime = new HashMap<>();
        Map<String, Integer> lastDays = new HashMap<>();
        int switches = 0;
        for (DayState s : states) {
            for (Map.Entry<String, String> e : s.regimeByAsset().entrySet()) {
                int days = s.daysInRegimeByAsset().get(e.getKey());
                String previous = lastRegime.get(e.getKey());
                if (previous == null || !previous.equals(e.getValue())) {
                    assertEquals(1, days, "bascule " + s.day());
                    switches++;
                } else {
                    assertEquals(lastDays.get(e.getKey()) + 1, days, "jour " + s.day());
                }
                lastRegime.put(e.getKey(), e.getValue());
                lastDays.put(e.getKey(), days);
            }
        }
        assertTrue(switches >= 2, "la fixture BTC doit contenir au moins une bascule de régime");
    }

    @Test
    @DisplayName("La sortie de la politique est bornée par les limites admin avant application")
    void outputClamped() {
        MultiLinkReplayEngine.Result r = run(startIdx + 60, (state, base) -> new ModulationSettings(0, 3, 99.0, 5.0));
        assertFalse(r.policyDays().isEmpty());
        for (MultiLinkReplayEngine.PolicyDay d : r.policyDays()) {
            assertEquals(LIMITS.getBiasMax().doubleValue(), d.applied().bias(), EPS);
            assertEquals(LIMITS.getPowerMax().doubleValue(), d.applied().power(), EPS);
            assertTrue(d.applied().exposureMax() - d.applied().exposureMin() >= LIMITS.getMinBandWidthPts().doubleValue() - EPS);
        }
    }

    @Test
    @DisplayName("Une politique active change bien le résultat (REGIME_BAND bear 0-30 vs CONSTANT)")
    void policyChangesOutcome() {
        MultiLinkReplayEngine.Result constant = run(candles.size() - 1, null);
        MultiLinkReplayEngine.Result band = run(candles.size() - 1, ModulationPolicies.from(new PolicySpec("REGIME_BAND",
                Map.of("bullMin", 20.0, "bullMax", 90.0, "bearMin", 0.0, "bearMax", 30.0))));
        assertFalse(band.policyDays().isEmpty());
        assertTrue(band.policyDays().stream().anyMatch(d -> d.applied().exposureMax() < 100.0));
        assertTrue(Math.abs(constant.finalValue() - band.finalValue()) > EPS);
    }
}
