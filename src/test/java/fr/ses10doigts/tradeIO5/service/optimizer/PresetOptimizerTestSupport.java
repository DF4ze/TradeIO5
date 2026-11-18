package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Entrées de test de l'optimiseur de presets : CSV D1 du repo, un lien par actif, jeux Bear / Bull réels des presets de l'app. */
final class PresetOptimizerTestSupport {

    static final int BEAR = RainbowAtrPresets.BEAR;
    static final LocalDate END = LocalDate.parse("2026-09-01");
    static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);

    private PresetOptimizerTestSupport() {
    }

    static List<MarketData> csv(String asset, int limit) throws IOException {
        List<MarketData> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("tools/rainbow-replay/data/d1_" + asset + ".csv"))) {
            if (out.size() >= limit) {
                break;
            }
            String[] p = line.split(",");
            out.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochMilli(Long.parseLong(p[0]))).pair(asset + "USDC")
                    .open(new BigDecimal(p[1])).high(new BigDecimal(p[2])).low(new BigDecimal(p[3])).close(new BigDecimal(p[4]))
                    .volume(BigDecimal.ZERO).build());
        }
        return out;
    }

    static ScorerInputs.AssetInput link(String asset, List<MarketData> candles) {
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]);
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TrendMixCalculator.Params.defaults(), RangeMapping.KEEP_PREVIOUS,
                sets, 0, 0, candles.size() - 1, null, Map.of(), Map.of(), Set.of(), 6);
        return new ScorerInputs.AssetInput(new LiveSlot(1L, 1L, asset, 1L, 1, 100.0, null), cfg, "TEST", 1.5, 10.0);
    }

    static ReferenceWallet wallet(List<ScorerInputs.AssetInput> assets, RainbowModulationControl limits) {
        return new ReferenceWallet(ScorerInputs.of(assets, 10_000.0, END, StartingProfile.BALANCED, limits), 90);
    }

    /** Espace réduit à deux paramètres libres par jeu : recherche rapide pour les tests. */
    static PresetSpaceSource smallSpace() {
        PresetSpaceConfig cfg = PresetSpaceConfig.defaults();
        for (PresetParam p : PresetParam.values()) {
            if (p != PresetParam.ATR_MULT_DOWN2 && p != PresetParam.SELL_FRACTION) {
                cfg = cfg.with(p.key(), false, null);
            }
        }
        PresetSpaceConfig result = cfg;
        return () -> result;
    }

    static PresetOptimizerConfig oneFold() {
        PresetOptimizerConfig d = PresetOptimizerConfig.defaults();
        return d.withFolds(List.of(LocalDate.parse("2024-05-15")), 6).withSearch(d.search().withSobolSamples(4));
    }
}
