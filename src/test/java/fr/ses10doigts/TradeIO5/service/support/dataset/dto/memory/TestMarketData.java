package fr.ses10doigts.TradeIO5.service.support.dataset.dto.memory;

import fr.ses10doigts.tradeIO5.model.dto.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.MarketDataSeries;
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
    public static MarketDataSeries simpleUptrend() {

        List<MarketData> data = new ArrayList<>();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = BigDecimal.valueOf(100);

        for (int i = 0; i < 50; i++) {

            BigDecimal open = price;
            BigDecimal close = price.add(BigDecimal.valueOf(1)); // hausse régulière
            BigDecimal high = close.add(BigDecimal.valueOf(0.5));
            BigDecimal low  = open.subtract(BigDecimal.valueOf(0.5));
            BigDecimal volume = BigDecimal.valueOf(1000 + i * 10);

            data.add(MarketData.builder()
                    .timeFrame(TimeFrame.HOUR_1)
                    .timestamp(start.plusSeconds(3600L * i))
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
                .timeFrame(TimeFrame.HOUR_1)
                .marketDatas(data)
                .build();
    }

    public static MarketDataSeries flatMarket() {
        return null;
    }

    public static MarketDataSeries downtrend() {
        return null;
    }

    public static MarketDataSeries volatileMarket() {
        return null;
    }
}