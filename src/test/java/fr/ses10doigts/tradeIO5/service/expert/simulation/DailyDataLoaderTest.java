package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyDataLoaderTest {

    @Test
    void concurrentLoadsOfSameAssetShareOneFetch() throws Exception {
        MarketDatasetEngine engine = mock(MarketDatasetEngine.class);
        DomainClock clock = () -> Instant.parse("2026-11-07T10:00:00Z");
        DailyDataset result = new DailyDataset(null, MarketDataSource.BINANCE, "BTCUSDT", false);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(engine.getDailyDatasetForAsset(eq("BTC"), anyInt(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            release.await();
            return result;
        });
        DailyDataLoader loader = new DailyDataLoader(engine, clock);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<DailyDataset>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> loader.load("BTC", MarketDataSource.BINANCE)));
            }
            Thread.sleep(300);
            release.countDown();
            for (Future<DailyDataset> f : futures) {
                assertSame(result, f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
