package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Market Dataset - Bucket")
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

    private final static Instant now = Instant.now();

    @Test
    void shouldAppendOnlyForwardInTime() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        Instant t2 = now.minus(Duration.ofSeconds(60*60));

        bucket.append(candle(now, bd(1), bd(2), bd(3), bd(1), bd(10)));
        bucket.append(candle(t2, bd(1), bd(1), bd(1), bd(1), bd(5))); // ignorée

        BucketView view = bucket.view(TimeFrame.H1, now);
        assertEquals(1, view.size());
        assertEquals(now, view.data().get(0).getTimestamp());
    }

    @Test
    void shouldRespectMaxSize() {
        Bucket bucket = new Bucket(TimeFrame.H1, 2);

        bucket.append(candle(ts(0), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(1), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(2), bd(1), bd(1), bd(1), bd(1), bd(1)));

        BucketView view = bucket.view(TimeFrame.H1, now);
        assertEquals(2, view.size());
        assertEquals(ts(1), view.data().get(0).getTimestamp());
        assertEquals(ts(2), view.data().get(1).getTimestamp());
    }

    @Test
    void shouldAggregateToHigherTimeFrame() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        bucket.append(candle(ts(0), bd(10), bd(12), bd(13), bd(9), bd(5)));
        bucket.append(candle(ts(1), bd(12), bd(11), bd(14), bd(10), bd(7)));
        bucket.append(candle(ts(2), bd(11), bd(15), bd(16), bd(11), bd(9)));
        bucket.append(candle(ts(3), bd(15), bd(14), bd(15), bd(13), bd(6)));

        BucketView h4 = bucket.view(TimeFrame.H4, now);

        assertEquals(1, h4.size());
        MarketData c = h4.data().get(0);

        assertEquals(bd(10), c.getOpen());
        assertEquals(bd(14), c.getClose());
        assertEquals(bd(16), c.getHigh());
        assertEquals(bd(9), c.getLow());
        assertEquals(bd(27), c.getVolume());
    }

    @Test
    void shouldThrowIfTimeFrameIsNotMultiple() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);
        BucketView view = bucket.view(TimeFrame.MIN5, now);

        assertSame(view.completeness(), CompletenessLevel.INCOMPLETE);
    }

    @Test
    void shouldWorkIfIsMultiple() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        assertNotNull(bucket.view(TimeFrame.M1, now));
    }

    @Test
    void shouldUseCacheUntilNewAppend() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        bucket.append(candle(ts(0), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(1), bd(1), bd(1), bd(1), bd(1), bd(1)));

        BucketView first = bucket.view(TimeFrame.H4, now);
        BucketView second = bucket.view(TimeFrame.H4, now);

        assertSame(first.data().get(0), second.data().get(0)); // même instance via cache

        bucket.append(candle(ts(2), bd(1), bd(1), bd(1), bd(1), bd(1)));
        BucketView third = bucket.view(TimeFrame.H4, now);

        assertNotSame(first, third); // cache invalidé
    }

    @Test
    @DisplayName("append d'une bougie de même timestamp que la dernière => remplacement (bougie en cours rafraîchie)")
    void shouldReplaceLastCandleWhenSameTimestamp() {
        Bucket bucket = new Bucket(TimeFrame.H1, 10);

        bucket.append(candle(ts(0), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(1), bd(1), bd(5), bd(5), bd(1), bd(1))); // partielle
        bucket.append(candle(ts(1), bd(1), bd(9), bd(9), bd(1), bd(2))); // version à jour

        BucketView view = bucket.view(TimeFrame.H1, now);
        assertEquals(2, view.size());
        assertEquals(0, bd(9).compareTo(view.data().getLast().getClose()));
    }

    @Test
    @DisplayName("fill insère l'historique plus ancien et les trous, sans écraser ni dépasser la dernière bougie")
    void shouldFillOlderHistoryAndHoles() {
        Bucket bucket = new Bucket(TimeFrame.H1, 100);
        bucket.append(candle(ts(10), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(13), bd(1), bd(1), bd(1), bd(1), bd(1)));

        int inserted = bucket.fill(java.util.List.of(
                candle(ts(8), bd(1), bd(1), bd(1), bd(1), bd(1)),   // plus ancien
                candle(ts(9), bd(1), bd(1), bd(1), bd(1), bd(1)),   // plus ancien
                candle(ts(11), bd(1), bd(1), bd(1), bd(1), bd(1)),  // trou
                candle(ts(13), bd(1), bd(7), bd(7), bd(1), bd(1)),  // déjà présent : inchangé
                candle(ts(14), bd(1), bd(1), bd(1), bd(1), bd(1)))); // après la dernière : ignoré

        assertEquals(3, inserted);
        BucketView view = bucket.view(TimeFrame.H1, now);
        assertEquals(5, view.size());
        assertEquals(ts(8), view.data().getFirst().getTimestamp());
        assertEquals(ts(13), view.data().getLast().getTimestamp());
        assertEquals(0, bd(1).compareTo(view.data().getLast().getClose()));
    }

    @Test
    @DisplayName("fill respecte maxSize en évinçant le plus ancien")
    void shouldFillRespectMaxSize() {
        Bucket bucket = new Bucket(TimeFrame.H1, 3);
        bucket.append(candle(ts(10), bd(1), bd(1), bd(1), bd(1), bd(1)));
        bucket.append(candle(ts(11), bd(1), bd(1), bd(1), bd(1), bd(1)));

        bucket.fill(java.util.List.of(
                candle(ts(8), bd(1), bd(1), bd(1), bd(1), bd(1)),
                candle(ts(9), bd(1), bd(1), bd(1), bd(1), bd(1))));

        assertEquals(3, bucket.size());
        assertEquals(ts(9), bucket.peekFirst().getTimestamp());
    }

    /* ===== helpers ===== */

    private static Instant ts(long hoursFromEpoch) {
        return Instant.ofEpochSecond(hoursFromEpoch * 3600);
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}