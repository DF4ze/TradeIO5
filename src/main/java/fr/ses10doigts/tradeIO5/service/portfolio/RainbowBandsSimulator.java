package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.RainbowBandsDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrBand;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSignal;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveCandleSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Bandes Rainbow recalculées à la demande (pointillé « simulé » avant le premier run) : mêmes indicateurs, même jeu de
 * paramètres (Trend Mix : jeu Bull/Bear choisi par le régime du jour) que la passe, sur les bougies D1 du lien. Les achats/ventes
 * virtuels ({@code action}) sont rejoués par {@link RainbowAtrReplay} sur toute la période affichée. Aucune écriture,
 * aucun état moteur, aucun appel authentifié.
 */
@Service
@RequiredArgsConstructor
public class RainbowBandsSimulator {

    private final MarketDatasetEngine datasetEngine;
    private final AffectationConfigResolver configResolver;

    /** Points du jour {@code from} à {@code to} inclus ; vide si la série D1 est absente ou trop courte. */
    public List<RainbowBandsDto.Point> simulate(RainbowLiveBinding binding, LocalDate from, LocalDate to) {
        AssetAffectation affectation = binding.getAffectation();
        MarketDataSource source = LiveCandleSource.of(binding.getWallet());
        Instant asOf = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        DailyDataset daily = datasetEngine.getDailyDatasetForAsset(binding.getAssetSymbol(),
                RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES, asOf, source);
        if (daily == null || daily.dataset() == null || daily.dataset().getMarketDatas() == null) {
            return List.of();
        }
        List<MarketData> candles = daily.dataset().getMarketDatas().stream()
                .filter(c -> !dayOf(c).isAfter(to)).toList();
        if (candles.isEmpty()) {
            return List.of();
        }
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        return trendMix(affectation, candles, ds, from);
    }

    private List<RainbowBandsDto.Point> trendMix(AssetAffectation affectation, List<MarketData> candles, RainbowAtrDataset ds,
                                                 LocalDate from) {
        RainbowLiveTrendConfigs.Resolved cfg = configResolver.trendConfig(affectation);
        TrendMixCalculator.Params tp = cfg.trend();
        TrendRegime[] regime = TrendMixCalculator.compute(candles, ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp).regime();
        int[] setOfBar = RainbowSetSelector.select(regime, cfg.range());
        RainbowAtrParamSet[] sets = cfg.sets();
        Map<Integer, String> actions = actions(ds, sets, setOfBar, firstIndex(candles, from));
        List<RainbowBandsDto.Point> out = new ArrayList<>();
        for (int i = 0; i < ds.size(); i++) {
            if (dayOf(candles.get(i)).isBefore(from)) {
                continue;
            }
            RainbowAtrParamSet set = sets[setOfBar[i]];
            out.add(point(ds, i, set.tuning(), set.name(), regime[i] == null ? null : regime[i].name(), actions.get(i)));
        }
        return out;
    }

    /** Index de la première bougie du jour {@code from} ou après (taille du dataset si aucune). */
    private static int firstIndex(List<MarketData> candles, LocalDate from) {
        for (int i = 0; i < candles.size(); i++) {
            if (!dayOf(candles.get(i)).isBefore(from)) {
                return i;
            }
        }
        return candles.size();
    }

    /**
     * Achats/ventes virtuels du moteur rejoués sur la période affichée (machine à froid au premier jour, comme le pine sur la
     * plage visible) : BUY / SELL par index de bougie. Indépendant du wallet et de l'état persisté.
     */
    private static Map<Integer, String> actions(RainbowAtrDataset ds, RainbowAtrParamSet[] sets, int[] setOfBar, int startIdx) {
        Map<Integer, String> out = new HashMap<>();
        if (startIdx >= ds.size()) {
            return out;
        }
        for (RainbowAtrReplay.DayRow row : RainbowAtrReplay.run(ds, sets, setOfBar, startIdx, ds.size() - 1)) {
            if (row.sellKind() != RainbowSignal.SellKind.NONE) {
                out.put(row.index(), "SELL");
            } else if (row.buyKind() != RainbowSignal.BuyKind.NONE) {
                out.put(row.index(), "BUY");
            }
        }
        return out;
    }

    private static RainbowBandsDto.Point point(RainbowAtrDataset ds, int i, RainbowAtrTuning tuning, String set, String regime, String action) {
        double close = ds.close(i);
        double sma = ds.sma(tuning.smaPeriod())[i];
        double atr = ds.atr(tuning.atrPeriod())[i];
        LocalDate day = Instant.ofEpochMilli(ds.time(i)).atOffset(ZoneOffset.UTC).toLocalDate();
        if (Double.isNaN(sma) || Double.isNaN(atr)) {
            return new RainbowBandsDto.Point(day, close, null, null, null, null, null, null, null, null, set, regime, action);
        }
        RainbowAtrBand b = RainbowAtrBand.of(close, sma, atr, tuning);
        return new RainbowBandsDto.Point(day, close, b.sma(), b.atr(), b.down2(), b.down1(), b.up1(), b.up2(), b.up3(), b.zone(),
                set, regime, action);
    }

    private static LocalDate dayOf(MarketData c) {
        return c.getTimestamp().atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
