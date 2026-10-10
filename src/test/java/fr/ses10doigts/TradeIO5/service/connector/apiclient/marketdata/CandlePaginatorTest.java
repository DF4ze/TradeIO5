package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CandlePaginator")
class CandlePaginatorTest {

    private static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");
    private static final int PAGE = 1000;

    private static Instant at(int index) {
        return TimeFrame.H1.addTo(T0, index);
    }

    private static MarketData candle(int index) {
        return MarketData.builder().pair("X").timeFrame(TimeFrame.H1).timestamp(at(index))
                .open(BigDecimal.ONE).high(BigDecimal.ONE).low(BigDecimal.ONE).close(BigDecimal.ONE)
                .volume(BigDecimal.ONE).build();
    }

    /** Simule Binance : bougies d'index [0, total) hors {@code holes}, startTime inclusif, endTime inclusif, page plafonnée. */
    private static CandlePaginator.PageFetcher exchange(int total, List<Integer> holes, Instant until, AtomicInteger calls) {
        return (start, pageLimit) -> {
            calls.incrementAndGet();
            return IntStream.range(0, total)
                    .filter(i -> !holes.contains(i))
                    .filter(i -> start == null || !at(i).isBefore(start))
                    .filter(i -> until == null || !at(i).isAfter(until))
                    .limit(Math.min(pageLimit, PAGE))
                    .mapToObj(CandlePaginatorTest::candle)
                    .toList();
        };
    }

    private static List<MarketData> run(int total, List<Integer> holes, int limit, AtomicInteger calls) {
        Instant until = at(total - 1);
        return CandlePaginator.paginate(T0, until, limit, PAGE, TimeFrame.H1, 50, 0, exchange(total, holes, until, calls));
    }

    private static void assertContiguousFromStart(List<MarketData> result, int expectedSize) {
        assertEquals(expectedSize, result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(at(i), result.get(i).getTimestamp());
        }
    }

    @Test
    @DisplayName("0 bougie : liste vide, un seul appel")
    void zeroCandles() {
        AtomicInteger calls = new AtomicInteger();
        List<MarketData> result = CandlePaginator.paginate(T0, at(10), 11, PAGE, TimeFrame.H1, 50, 0,
                exchange(0, List.of(), at(10), calls));
        assertTrue(result.isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("1 bougie : un appel")
    void oneCandle() {
        AtomicInteger calls = new AtomicInteger();
        assertContiguousFromStart(run(1, List.of(), 1, calls), 1);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("1000 bougies : une page pleine puis arrêt sur cursor > until, sans appel superflu")
    void exactlyOnePage() {
        AtomicInteger calls = new AtomicInteger();
        assertContiguousFromStart(run(1000, List.of(), 1000, calls), 1000);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("1001 bougies : 2 appels, aucun doublon")
    void onePageAndOne() {
        AtomicInteger calls = new AtomicInteger();
        assertContiguousFromStart(run(1001, List.of(), 1001, calls), 1001);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("2500 bougies : 3 appels, ordre croissant contigu")
    void multiplePages() {
        AtomicInteger calls = new AtomicInteger();
        assertContiguousFromStart(run(2500, List.of(), 2500, calls), 2500);
        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("trou réel au milieu (bougies absentes chez l'exchange) : la pagination ne s'arrête pas")
    void holeInTheMiddle() {
        AtomicInteger calls = new AtomicInteger();
        List<MarketData> result = run(2500, List.of(1500, 1501), 2500, calls);
        assertEquals(2498, result.size());
        assertEquals(at(2499), result.getLast().getTimestamp());
    }

    @Test
    @DisplayName("doublons renvoyés par l'exchange : ignorés")
    void duplicatesAreDropped() {
        AtomicInteger calls = new AtomicInteger();
        // Chaque page repart de la dernière bougie déjà reçue (chevauchement d'une bougie)
        CandlePaginator.PageFetcher overlapping = (start, pageLimit) -> {
            calls.incrementAndGet();
            int first = (int) java.time.Duration.between(T0, start).toHours();
            int from = Math.max(first - 1, 0);
            List<MarketData> page = new ArrayList<>();
            for (int i = from; i < Math.min(from + pageLimit, 2000); i++) {
                page.add(candle(i));
            }
            return page;
        };
        List<MarketData> result = CandlePaginator.paginate(T0, at(1999), 0, PAGE, TimeFrame.H1, 50, 0, overlapping);
        assertContiguousFromStart(result, 2000);
    }

    @Test
    @DisplayName("limit plafonne le total et la taille de la dernière page")
    void limitCapsTotal() {
        AtomicInteger calls = new AtomicInteger();
        assertContiguousFromStart(run(5000, List.of(), 1500, calls), 1500);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("garde-fou maxPages : préfixe contigu retourné, pas d'exception")
    void maxPagesGuard() {
        AtomicInteger calls = new AtomicInteger();
        Instant until = at(4999);
        List<MarketData> result = CandlePaginator.paginate(T0, until, 5000, PAGE, TimeFrame.H1, 2, 0,
                exchange(5000, List.of(), until, calls));
        assertContiguousFromStart(result, 2000);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("erreur sur la 1re page : propagée")
    void firstPageErrorPropagates() {
        assertThrows(IllegalStateException.class, () -> CandlePaginator.paginate(T0, at(5000), 5000, PAGE, TimeFrame.H1, 50, 0,
                (start, pageLimit) -> {
                    throw new IllegalStateException("429");
                }));
    }

    @Test
    @DisplayName("erreur sur une page suivante : préfixe déjà obtenu conservé")
    void laterPageErrorKeepsPrefix() {
        AtomicInteger calls = new AtomicInteger();
        CandlePaginator.PageFetcher flaky = (start, pageLimit) -> {
            if (calls.incrementAndGet() == 3) {
                throw new IllegalStateException("429");
            }
            int first = (int) java.time.Duration.between(T0, start).toHours();
            return IntStream.range(first, first + pageLimit).mapToObj(CandlePaginatorTest::candle).toList();
        };
        List<MarketData> result = CandlePaginator.paginate(T0, at(9999), 10000, PAGE, TimeFrame.H1, 50, 0, flaky);
        assertContiguousFromStart(result, 2000);
    }

    @Test
    @DisplayName("sans since mais limit > page : remonte de limit bougies avant until")
    void noSinceDerivedFromLimit() {
        AtomicInteger calls = new AtomicInteger();
        Instant until = at(2999);
        List<MarketData> result = CandlePaginator.paginate(null, until, 2500, PAGE, TimeFrame.H1, 50, 0,
                exchange(3000, List.of(), until, calls));
        assertEquals(2500, result.size());
        assertEquals(at(500), result.getFirst().getTimestamp());
        assertEquals(until, result.getLast().getTimestamp());
    }

    @Test
    @DisplayName("sans since, limit <= page : appel unique inchangé")
    void noSinceSingleCall() {
        AtomicInteger calls = new AtomicInteger();
        List<MarketData> result = CandlePaginator.paginate(null, at(2999), 300, PAGE, TimeFrame.H1, 50, 0,
                exchange(3000, List.of(), at(2999), calls));
        assertEquals(1, calls.get());
        assertEquals(300, result.size());
    }
}
