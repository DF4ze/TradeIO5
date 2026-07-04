package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

// Le MarketDatasetCache (singleton Spring) est indexé par flux natif
// (symbol + timeFrame + source + providerParam), volontairement SANS endTime/lookBack
// (cf. BucketKey / MarketDatasetEngineTest.shouldReuseSameStateAcrossRequestsWithDifferentEndTimeAndLookBack).
// Plusieurs méthodes ci-dessous réutilisent les mêmes symboles ("fastTF", "slowTF") avec le
// même TrendType : sans isolation du contexte Spring entre méthodes, elles partageraient le
// même Bucket et se pollueraient mutuellement (cf. bug résolu ici).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Market Dataset - *Engine - Spring")
@SpringBootTest
class MarketDatasetEngineSpringTest {

    @Autowired
    private MarketDatasetEngine marketDatasetEngine;


    @Test
    void getDataset_noException() {
        MarketDatasetRequest mdrFast = new MarketDatasetRequest(
                "fastTF", TimeFrame.H1, 50, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDataset fastDataset = marketDatasetEngine.getDataset(mdrFast);
    }

    @Test
    void getDataset_withException() {
        int nbUnit = 25;
        MarketDatasetRequest mdrFast = new MarketDatasetRequest(
                "fastTF", TimeFrame.MIN1, nbUnit, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);

        assertThrows(IllegalArgumentException.class,
                () -> marketDatasetEngine.getDataset(mdrFast));

    }

    @Test
    void getDataset_Integration_DatasetOpen() {
        int nbUnit = 25;

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime ldt = LocalDateTime.parse("2026-01-24 02:24", formatter);
        Instant instant = ldt.atZone(TimeFrame.DEFAULT_ZONE).toInstant();

        MarketDatasetRequest mdrSlow = new MarketDatasetRequest(
                "slowTF", TimeFrame.W1, nbUnit, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDataset slowDataset = marketDatasetEngine.getDataset(mdrSlow);


        assertEquals(nbUnit, slowDataset.getSize());
        assertSame(TimeFrame.W1, slowDataset.getMarketDatas().get(0).getTimeFrame());
        assertFalse(slowDataset.isComplete());
        assertEquals("slowTF", slowDataset.getPair());

    }

    @Test
    void getDataset_Integration_closed() {
        int nbUnit = 2;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime ldt = LocalDateTime.parse("2026-01-01 00:00", formatter);
        Instant instant = ldt.atZone(TimeFrame.DEFAULT_ZONE).toInstant();

        MarketDatasetRequest mdrFast = new MarketDatasetRequest(
                "fastTF", TimeFrame.H1, nbUnit, instant, MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDataset fastDataset = marketDatasetEngine.getDataset(mdrFast);

        MarketDatasetRequest mdrSlow = new MarketDatasetRequest(
                "slowTF", TimeFrame.D1, nbUnit, instant, MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDataset slowDataset = marketDatasetEngine.getDataset(mdrSlow);

        MarketDatasetRequest mdrVSlow = new MarketDatasetRequest(
                "vslowTF", TimeFrame.M1, nbUnit, instant, MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDataset vslowDataset = marketDatasetEngine.getDataset(mdrVSlow);


        assertEquals(nbUnit, fastDataset.getSize());
        assertSame(TimeFrame.H1, fastDataset.getMarketDatas().get(0).getTimeFrame());
        assertTrue(fastDataset.isComplete());
        assertEquals("fastTF", fastDataset.getPair());

        assertEquals(nbUnit, slowDataset.getSize());
        assertSame(TimeFrame.D1, slowDataset.getMarketDatas().get(0).getTimeFrame());
        assertTrue(slowDataset.isComplete());
        assertEquals("slowTF", slowDataset.getPair());

        assertEquals(nbUnit, vslowDataset.getSize());
        assertSame(TimeFrame.M1, vslowDataset.getMarketDatas().get(0).getTimeFrame());
        assertTrue(vslowDataset.isComplete());
        assertEquals("vslowTF", vslowDataset.getPair());

    }

}