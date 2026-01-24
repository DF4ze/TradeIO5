package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;

import java.time.Instant;
import java.util.List;

public interface MarketDataProvider {
    /**
     * Chargement complet (statique ou initial)
     */
    MarketDataset fullLoad(MarketDatasetRequest request);

    /**
     * Chargement incrémental (sources live uniquement)
     */
    MarketDataset loadSince(MarketDatasetRequest request);

    /**
     * Fetch direct (ticker / bougie courante)
     */
    List<MarketData> fetchMarketData(
            String symbol,
            TimeFrame timeframe,
            Instant time,
            int limit
    );

}
