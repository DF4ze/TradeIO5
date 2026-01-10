package fr.ses10doigts.TradeIO5.service.support.dataset.dto.memory;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;

public final class InMemoryDatasets {

    public static MarketDataset load(DatasetType type, TimeFrame timeFrame) {

        return switch (type) {
            case FLAT -> new MarketDataset(
                    "memory_flat",
                    TestMarketData.flatMarket(timeFrame)
            );
            case UPTREND -> new MarketDataset(
                    "memory_uptrend",
                    TestMarketData.simpleUptrend(timeFrame)
            );
            case DOWNTREND -> new MarketDataset(
                    "memory_downtrend",
                    TestMarketData.downtrend(timeFrame)
            );
            case VOLATILE -> new MarketDataset(
                    "memory_volatile",
                    TestMarketData.volatileMarket(timeFrame)
            );
        };
    }
}
