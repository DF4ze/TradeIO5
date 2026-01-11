package fr.ses10doigts.tradeIO5.service.support.dataset.dto.memory;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class TestMarketData {

    private TestMarketData() {}

    /**
     * Marché en tendance haussière simple et régulière
     * Utile pour EMA / MACD
     */
    public static MarketDataSeries simpleUptrend(MarketDataRequest request) {
        TimeFrame timeFrame = request.getTimeFrame();
        int lookback = request.getLookback();

        List<MarketData> data = new ArrayList<>();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = BigDecimal.valueOf(100);

        for (int i = 0; i < lookback; i++) {

            BigDecimal open = price;
            BigDecimal close = price.add(BigDecimal.valueOf(1)); // hausse régulière
            BigDecimal high = close.add(BigDecimal.valueOf(0.5));
            BigDecimal low  = open.subtract(BigDecimal.valueOf(0.5));
            BigDecimal volume = BigDecimal.valueOf(1000 + i * 10L);

            data.add(MarketData.builder()
                    .timeFrame(timeFrame)
                    .timestamp(start.plusSeconds(timeFrame.getNbSeconde() * i))
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build()
            );

            price = close;
        }

        return MarketDataSeries.builder()
                .timeFrame(timeFrame)
                .marketDatas(data)
                .build();
    }

    public static MarketDataSeries flatMarket(MarketDataRequest request) {
        return null;
    }

    public static MarketDataSeries downtrend(MarketDataRequest request) {
        return null;
    }

    public static MarketDataSeries volatileMarket(MarketDataRequest request) {
        return null;
    }
}