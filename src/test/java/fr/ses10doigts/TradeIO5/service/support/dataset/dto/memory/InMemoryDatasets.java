package fr.ses10doigts.TradeIO5.service.support.dataset.dto.memory;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;

public final class InMemoryDatasets {

    public static MarketDataset load(DatasetType type) {

        return switch (type) {
            case FLAT -> new MarketDataset(
                    "memory_flat",
                    TestMarketData.flatMarket()
            );
            case UPTREND -> new MarketDataset(
                    "memory_uptrend",
                    TestMarketData.simpleUptrend()
            );
            case DOWNTREND -> new MarketDataset(
                    "memory_downtrend",
                    TestMarketData.downtrend()
            );
            case VOLATILE -> new MarketDataset(
                    "memory_volatile",
                    TestMarketData.volatileMarket()
            );
        };
    }
}
