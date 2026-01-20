package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BucketTest {

    private static MarketData candle(
            Instant ts,
            BigDecimal open,
            BigDecimal close,
            BigDecimal high,
            BigDecimal low,
            BigDecimal volume
    ) {
        return MarketData.builder()
                .pair("BTC/USDT")
                .timeFrame(TimeFrame.H1)
                .timestamp(ts)
                .open(open)
                .close(close)
                .high(high)
                .low(low)
                .volume(volume)
                .build();
    }

    @Test
    void shouldAppendOnlyForwardInTime() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        Instant t1 = Instant.parse("2024-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2024-01-01T11:00:00Z");

        bucket.append(candle(t2, bd(1), bd(2), bd(3), bd(1), bd(10)));
        bucket.append(candle(t1, bd(1), bd(1), bd(1), bd(1), bd(5))); // ignorée

        List<MarketData> view = bucket.view(TimeFrame.H1);
        assertEquals(1, view.size());
        assertEquals(t2, view.get(0).getTimestamp());
    }

    @Test
    void shouldRespectMaxSize() {
        Bucket bucket = new Bucket(TimeFrame.H1, 2);

        bucket.append(candle(ts(0), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(1), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(2), bd(1), bd(1), bd(1), bd(1), bd(1)));

        List<MarketData> view = bucket.view(TimeFrame.H1);
        assertEquals(2, view.size());
        assertEquals(ts(1), view.get(0).getTimestamp());
        assertEquals(ts(2), view.get(1).getTimestamp());
    }

    @Test
    void shouldAggregateToHigherTimeFrame() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        bucket.append(candle(ts(0), bd(10), bd(12), bd(13), bd(9), bd(5)));
        bucket.append(candle(ts(1), bd(12), bd(11), bd(14), bd(10), bd(7)));
        bucket.append(candle(ts(2), bd(11), bd(15), bd(16), bd(11), bd(9)));
        bucket.append(candle(ts(3), bd(15), bd(14), bd(15), bd(13), bd(6)));

        List<MarketData> h4 = bucket.view(TimeFrame.H4);

        assertEquals(1, h4.size());
        MarketData c = h4.get(0);

        assertEquals(bd(10), c.getOpen());
        assertEquals(bd(14), c.getClose());
        assertEquals(bd(16), c.getHigh());
        assertEquals(bd(9), c.getLow());
        assertEquals(bd(27), c.getVolume());
    }

    @Test
    void shouldThrowIfTimeFrameIsNotMultiple() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        assertThrows(IllegalArgumentException.class,
                () -> bucket.view(TimeFrame.MIN5));
    }

    @Test
    void shouldUseCacheUntilNewAppend() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        bucket.append(candle(ts(0), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(1), bd(1), bd(1), bd(1), bd(1), bd(1)));

        List<MarketData> first = bucket.view(TimeFrame.H4);
        List<MarketData> second = bucket.view(TimeFrame.H4);

        assertSame(first.get(0), second.get(0)); // même instance via cache

        bucket.append(candle(ts(2), bd(1), bd(1), bd(1), bd(1), bd(1)));
        List<MarketData> third = bucket.view(TimeFrame.H4);

        assertNotSame(first, third); // cache invalidé
    }

    /* ===== helpers ===== */

    private static Instant ts(long hoursFromEpoch) {
        return Instant.ofEpochSecond(hoursFromEpoch * 3600);
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}