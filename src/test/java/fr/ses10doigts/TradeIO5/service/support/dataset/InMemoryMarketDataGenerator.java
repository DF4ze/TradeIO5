package fr.ses10doigts.tradeIO5.service.support.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryMarketDataGenerator {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryMarketDataGenerator.class);

    private InMemoryMarketDataGenerator() {}

    /**
     * Marché en tendance haussière simple et régulière
     * Utile pour EMA / MACD
     */
    public static MarketDataset simpleUptrend(MarketDatasetRequest request) {
        TimeFrame timeFrame = request.timeFrame();
        int lookback = request.lookback();

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
                    .timestamp(start.plusSeconds(timeFrame.getNbSeconds() * i))
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build()
            );

            price = close;
        }

        if( data.isEmpty() )
            logger.error("Error generating data!!!");
        else
            logger.debug("UP_TREND : Generated values start at {} end at {}", data.get(0).getClose(), data.get(data.size()-1).getClose());

        return MarketDataset.builder()
                .timeFrame(timeFrame)
                .marketDatas(data)
                .build();
    }

    public static MarketDataset flatMarket(MarketDatasetRequest request) {
        TimeFrame timeFrame = request.timeFrame();
        int lookback = request.lookback();

        List<MarketData> data = new ArrayList<>();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = BigDecimal.valueOf(100);

        for (int i = 0; i < lookback; i++) {

            BigDecimal open = price;
            BigDecimal close = price.add(BigDecimal.valueOf(0)); // Flat
            BigDecimal high = close.add(BigDecimal.valueOf(0.5));
            BigDecimal low  = open.subtract(BigDecimal.valueOf(0.5));
            BigDecimal volume = BigDecimal.valueOf( 1000 + i * 10L);

            data.add(MarketData.builder()
                    .timeFrame(timeFrame)
                    .timestamp(start.plusSeconds(timeFrame.getNbSeconds() * i))
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build()
            );

            price = close;
        }

        if( data.isEmpty() )
            logger.error("Error generating data!!!");
        else
            logger.debug("DOWN_TREND : Generated values start at {} end at {}", data.get(0).getClose(), data.get(data.size()-1).getClose());

        return MarketDataset.builder()
                .timeFrame(timeFrame)
                .marketDatas(data)
                .build();
    }

    public static MarketDataset downtrend(MarketDatasetRequest request) {
        TimeFrame timeFrame = request.timeFrame();
        int lookback = request.lookback();

        List<MarketData> data = new ArrayList<>();

        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = BigDecimal.valueOf(100);

        for (int i = 0; i < lookback; i++) {

            BigDecimal open = price;
            BigDecimal close = price.add(BigDecimal.valueOf(-1)); // baisse régulière
            BigDecimal high = close.add(BigDecimal.valueOf(0.5));
            BigDecimal low  = open.subtract(BigDecimal.valueOf(0.5));
            BigDecimal volume = BigDecimal.valueOf( 1000 + i * 10L);

            data.add(MarketData.builder()
                    .timeFrame(timeFrame)
                    .timestamp(start.plusSeconds(timeFrame.getNbSeconds() * i))
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build()
            );

            price = close;
        }

        if( data.isEmpty() )
            logger.error("Error generating data!!!");
        else
            logger.debug("DOWN_TREND : Generated values start at {} end at {}", data.get(0).getClose(), data.get(data.size()-1).getClose());

        return MarketDataset.builder()
                .timeFrame(timeFrame)
                .marketDatas(data)
                .build();
    }

    public static MarketDataset volatileMarket(MarketDatasetRequest request) {
        return null;
    }
}