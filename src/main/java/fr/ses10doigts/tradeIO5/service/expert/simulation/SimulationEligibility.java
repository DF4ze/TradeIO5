package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.EligibilityDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ProviderEligibilityDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.market.CandleRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssets;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Éligibilité à la simulation, DÉRIVÉE des presets (jamais figée) : historique D1 requis = max(warmup du Rainbow
 * ({@link RainbowAtrDataset#warmup}), warmup du Trend ({@link TrendMixCalculator.Params#warmup()}), fenêtre d'analyse du
 * preset). Aucune lecture exchange : les bougies disponibles sont celles déjà en cache DB, la profondeur servable d'un
 * provider vient de {@link MarketDataProviderLimits#maxD1Candles}.
 */
@Component
@RequiredArgsConstructor
public class SimulationEligibility {

    private static final int DAYS_PER_MONTH = 30;

    private final CandleRepository candleRepository;
    private final ExpertAssets assets;

    /** Bougies nécessaires aux réglages donnés ({@code null} = réglage non concerné). */
    public static int requiredPoints(TrendMixCalculator.Params trend, RainbowAtrTuning rainbow, Integer analysisWindowMonths) {
        int fromTrend = trend == null ? 0 : trend.warmup();
        int fromRainbow = rainbow == null ? 0 : RainbowAtrDataset.warmup(rainbow.smaPeriod(), rainbow.atrPeriod()) + 1;
        int fromWindow = analysisWindowMonths == null ? 0 : analysisWindowMonths * DAYS_PER_MONTH;
        return Math.max(fromTrend, Math.max(fromRainbow, fromWindow));
    }

    /** Éligibilité de l'actif pour {@code requiredPoints}, par provider actif, sans aucun appel exchange. */
    public EligibilityDto forAsset(String symbol, int requiredPoints) {
        List<ProviderEligibilityDto> providers = assets.providers(symbol).stream().map(p -> provider(p, requiredPoints)).toList();
        return new EligibilityDto(requiredPoints, providers);
    }

    /** {@code true} si aucun provider de l'actif (ni {@code requested}) ne peut servir {@code requiredPoints} bougies. */
    public boolean unreachable(String symbol, MarketDataSource requested, int requiredPoints) {
        boolean requestedOk = requested != null && MarketDataProviderLimits.maxD1Candles(requested) >= requiredPoints;
        return !requestedOk && assets.providers(symbol).stream()
                .noneMatch(p -> MarketDataProviderLimits.maxD1Candles(p.getSource()) >= requiredPoints);
    }

    private ProviderEligibilityDto provider(AssetProvider p, int requiredPoints) {
        int cached = (int) candleRepository.countBySourceAndPairAndTimeFrame(p.getSource(), p.getProviderSymbol(), TimeFrame.D1);
        int depth = MarketDataProviderLimits.maxD1Candles(p.getSource());
        return new ProviderEligibilityDto(p.getSource().name(), cached,
                depth == MarketDataProviderLimits.MAX_D1_HISTORY_CANDLES ? null : depth,
                cached >= requiredPoints, depth >= requiredPoints);
    }
}
