package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.AthCapSpec;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSignal;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Plafond de mise ATH utilisateur : parité, achat, vente, état du moteur, défauts, validation")
class AthCapReplayTest {

    private static final LocalDate START = LocalDate.parse("2024-06-10");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);
    private static final TrendMixCalculator.Params TREND = TrendMixCalculator.Params.defaults();
    private static final double EPS = 1e-9;

    private final List<MarketData> candles = SimulationFixtures.csv("BTC");
    private final RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
    private final RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
    private int startIdx;

    AthCapReplayTest() throws IOException {
        while (candles.get(startIdx).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(START)) {
            startIdx++;
        }
    }

    private MultiLinkReplayEngine.Result run(AthCap cap) {
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TREND, RangeMapping.KEEP_PREVIOUS, sets, startIdx,
                startIdx, candles.size() - 1, null, Map.of(), Map.of(), Set.of(), 6);
        MultiLinkReplayEngine.Link link = new MultiLinkReplayEngine.Link(new LiveSlot(1L, 1L, "BTC", 1L, 1, 100.0, null), cfg, 0.0);
        return MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(link), 1000.0, ModulationSettings.neutral(), LIMITS,
                Map.of(), Map.of(), Map.of(), Map.of(), null, null, cap));
    }

    private MultiLinkReplayEngine.Result runWithoutCapArgument() {
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TREND, RangeMapping.KEEP_PREVIOUS, sets, startIdx,
                startIdx, candles.size() - 1, null, Map.of(), Map.of(), Set.of(), 6);
        MultiLinkReplayEngine.Link link = new MultiLinkReplayEngine.Link(new LiveSlot(1L, 1L, "BTC", 1L, 1, 100.0, null), cfg, 0.0);
        return MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(link), 1000.0, ModulationSettings.neutral(), LIMITS,
                Map.of(), Map.of(), Map.of(), Map.of(), null));
    }

    private RainbowAtrReplay.Result replay(AthCap cap) {
        int[] setOfBar = RainbowSetSelector.select(
                TrendMixCalculator.compute(candles, ds.sma(TREND.smaPeriod()), ds.atr(TREND.atrPeriod()), TREND).regime(),
                RangeMapping.KEEP_PREVIOUS);
        return RainbowAtrReplay.runFull(ds, sets, setOfBar, startIdx, candles.size() - 1, cap);
    }

    private double baseOf(String setName) {
        return Arrays.stream(sets).filter(s -> s.name().equals(setName)).findFirst().orElseThrow().globals().baseAmount();
    }

    private static void assertSameResult(MultiLinkReplayEngine.Result expected, MultiLinkReplayEngine.Result actual) {
        assertFalse(expected.walletDays().isEmpty());
        assertEquals(expected.linkDays(), actual.linkDays());
        assertEquals(expected.walletDays(), actual.walletDays());
        assertEquals(expected.trades(), actual.trades());
        assertEquals(expected.signals(), actual.signals());
        assertEquals(expected.finalValue(), actual.finalValue(), 0.0);
        assertEquals(expected.fixedFinalValue(), actual.fixedFinalValue(), 0.0);
    }

    @Test
    @DisplayName("Parité : athCap null / vide == signature historique, champ par champ")
    void parityWithoutCap() {
        MultiLinkReplayEngine.Result historical = runWithoutCapArgument();
        assertSameResult(historical, run(null));
        assertSameResult(historical, run(new AthCap(null, null)));
        assertEquals(0, historical.stakeStats().cappedBuys());
    }

    @Test
    @DisplayName("Sans plafond la mise du signal dépasse largement la base (le test n'est pas vide)")
    void uncappedStakeIsHigh() {
        assertTrue(run(null).stakeStats().maxStakeMultiple() > 3.0);
    }

    @Test
    @DisplayName("Plafond d'achat : aucun achat du signal > buyCap × base ; mise finale bornée par le signal modulé")
    void buyCapRespected() {
        double cap = 2.0;
        MultiLinkReplayEngine.Result capped = run(new AthCap(cap, null));
        assertTrue(capped.stakeStats().maxStakeMultiple() <= cap + EPS);
        assertTrue(capped.stakeStats().cappedBuys() > 0);
        for (MultiLinkReplayEngine.LinkDay d : capped.linkDays()) {
            if (d.wantedAction() == RainbowLiveAction.BUY) {
                assertTrue(d.wantedAmountUsdc() <= cap * baseOf(d.setName()) + EPS);
            }
        }
        assertTrue(capped.stakeStats().maxFinalStakeMultiple() >= 0.0);
    }

    @Test
    @DisplayName("Plafond >= multiplicateur maximal observé : aucun effet")
    void highCapHasNoEffect() {
        MultiLinkReplayEngine.Result none = run(null);
        double highCap = Math.ceil(none.stakeStats().maxStakeMultiple()) + 1;
        MultiLinkReplayEngine.Result capped = run(new AthCap(highCap, null));
        assertSameResult(none, capped);
        assertEquals(0, capped.stakeStats().cappedBuys());
    }

    @Test
    @DisplayName("maxStakeMultiple = max des achats voulus du signal / base")
    void stakeStatsMatchDays() {
        MultiLinkReplayEngine.Result r = run(null);
        double max = r.linkDays().stream().filter(d -> d.wantedAction() == RainbowLiveAction.BUY)
                .mapToDouble(d -> d.wantedAmountUsdc() / baseOf(d.setName())).max().orElse(0.0);
        assertEquals(max, r.stakeStats().maxStakeMultiple(), 1e-6);
    }

    @Test
    @DisplayName("Plafond de vente : quantité demandée <= celle du facteur plafonné, jamais > position ; sellCap 0 = aucune vente ATH")
    void sellCapRespected() {
        RainbowAtrReplay.Result none = replay(null);
        assertTrue(none.rows().stream().anyMatch(r -> r.sellKind() == RainbowSignal.SellKind.SELL));
        double maxSellFraction = Arrays.stream(sets).mapToDouble(s -> s.tuning().sellFraction()).max().orElseThrow();
        RainbowAtrReplay.Result capped = replay(new AthCap(null, 1.0));
        for (RainbowAtrReplay.DayRow r : capped.rows()) {
            if (r.sellKind() == RainbowSignal.SellKind.SELL) {
                assertTrue(r.sellFraction() <= Math.min(1.0, maxSellFraction) + EPS);
            }
            assertTrue(r.position() >= -EPS);
        }
        RainbowAtrReplay.Result zero = replay(new AthCap(null, 0.0));
        assertTrue(zero.rows().stream().noneMatch(r -> r.sellKind() == RainbowSignal.SellKind.SELL));
    }

    @Test
    @DisplayName("État du moteur : position virtuelle = montants réellement demandés après plafond")
    void engineStateFollowsCappedAmounts() {
        AthCap cap = new AthCap(2.0, 1.0);
        MultiLinkReplayEngine.Result r = run(cap);
        List<MultiLinkReplayEngine.LinkDay> days = r.linkDays();
        for (int k = 0; k + 1 < days.size(); k++) {
            MultiLinkReplayEngine.LinkDay d = days.get(k);
            RainbowSignal s = r.signals().get(k).audit().signal();
            double base = baseOf(d.setName());
            double buy = s.buyKind() == RainbowSignal.BuyKind.NONE ? 0.0 : base * Math.min(s.buyAthFactor() * s.multZone(), cap.buyCap());
            double sell = s.sellKind() == RainbowSignal.SellKind.NONE ? 0.0 : s.sellFraction() * d.virtualPosition();
            assertEquals(Math.max(0.0, d.virtualPosition() - sell + buy / d.dayClose()), days.get(k + 1).virtualPosition(), 1e-6);
        }
    }

    @Test
    @DisplayName("Défauts par profil : Prudent 3/1, Équilibré 6/2, Dynamique 10/3, Full 15/5, CUSTOM sans défaut")
    void profileDefaults() {
        assertEquals(new AthCap(3d, 1d), ModulationDefaults.athCapOf(StartingProfile.PRUDENT));
        assertEquals(new AthCap(6d, 2d), ModulationDefaults.athCapOf(StartingProfile.BALANCED));
        assertEquals(new AthCap(10d, 3d), ModulationDefaults.athCapOf(StartingProfile.DYNAMIC));
        assertEquals(new AthCap(15d, 5d), ModulationDefaults.athCapOf(StartingProfile.FULL));
        assertNull(ModulationDefaults.athCapOf(StartingProfile.CUSTOM));
    }

    @Test
    @DisplayName("Champ athCap : profil = défauts, buyCap / sellCap explicites surchargent")
    void specResolution() {
        assertNull(AthCapResolver.resolve(null, "athCap"));
        assertNull(AthCapResolver.resolve(new AthCapSpec(null, null, null), "athCap"));
        assertNull(AthCapResolver.resolve(new AthCapSpec("CUSTOM", null, null), "athCap"));
        assertEquals(new AthCap(6d, 2d), AthCapResolver.resolve(new AthCapSpec("balanced", null, null), "athCap"));
        assertEquals(new AthCap(8d, 2d), AthCapResolver.resolve(new AthCapSpec("BALANCED", 8d, null), "athCap"));
        assertEquals(new AthCap(15d, 0d), AthCapResolver.resolve(new AthCapSpec("FULL", null, 0d), "athCap"));
        assertEquals(new AthCap(4d, null), AthCapResolver.resolve(new AthCapSpec(null, 4d, null), "athCap"));
    }

    @Test
    @DisplayName("Validation par champ : buyCap >= 1, sellCap >= 0, profil connu")
    void specValidation() {
        ExpertValidationException buy = assertThrows(ExpertValidationException.class,
                () -> AthCapResolver.resolve(new AthCapSpec(null, 0.5, null), "athCap"));
        assertTrue(buy.getFields().containsKey("athCap.buyCap"));
        ExpertValidationException sell = assertThrows(ExpertValidationException.class,
                () -> AthCapResolver.resolve(new AthCapSpec(null, null, -1d), "athCap"));
        assertTrue(sell.getFields().containsKey("athCap.sellCap"));
        ExpertValidationException profile = assertThrows(ExpertValidationException.class,
                () -> AthCapResolver.resolve(new AthCapSpec("YOLO", null, null), "policies[2].athCap"));
        assertTrue(profile.getFields().containsKey("policies[2].athCap.profile"));
        ExpertValidationException all = assertThrows(ExpertValidationException.class,
                () -> AthCapResolver.resolve(new AthCapSpec("YOLO", Double.NaN, -2d), "athCap"));
        assertEquals(3, all.getFields().size());
    }

    @Test
    @DisplayName("AthCap : multiplicateur / facteur effectifs et libellé")
    void capHelpers() {
        assertEquals(10.0, AthCap.effectiveBuyMultiplier(null, 10.0), 0.0);
        assertEquals(10.0, AthCap.effectiveBuyMultiplier(new AthCap(null, 1d), 10.0), 0.0);
        assertEquals(6.0, AthCap.effectiveBuyMultiplier(new AthCap(6d, null), 10.0), 0.0);
        assertEquals(4.0, AthCap.effectiveBuyMultiplier(new AthCap(6d, null), 4.0), 0.0);
        assertEquals(2.0, AthCap.effectiveSellFactor(new AthCap(null, 2d), 5.0), 0.0);
        assertEquals(1.5, AthCap.effectiveSellFactor(new AthCap(null, 2d), 1.5), 0.0);
        assertTrue(AthCap.buyCapped(new AthCap(6d, null), 6.5));
        assertFalse(AthCap.buyCapped(new AthCap(6d, null), 6.0));
        assertFalse(AthCap.buyCapped(null, 100.0));
        assertEquals("aucun", new AthCap(null, null).label());
        assertEquals("achat ×6 / vente ×2", new AthCap(6d, 2d).label());
    }
}
