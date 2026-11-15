package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Index de jours des rejeux")
class ReplayDaysTest {

    private final RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(SimulationFixtures.synthetic(30, Instant.parse("2026-11-01T00:00:00Z")));
    private final LocalDate first = LocalDate.parse("2026-10-02"), last = LocalDate.parse("2026-10-31");

    @Test
    void indexOfDay() {
        assertEquals(0, ReplayDays.indexOfDay(ds, first));
        assertEquals(29, ReplayDays.indexOfDay(ds, last));
        assertEquals(-1, ReplayDays.indexOfDay(ds, last.plusDays(1)));
    }

    @Test
    void lastIndexOnOrBefore() {
        assertEquals(-1, ReplayDays.lastIndexOnOrBefore(ds, first.minusDays(1)));
        assertEquals(5, ReplayDays.lastIndexOnOrBefore(ds, first.plusDays(5)));
        assertEquals(29, ReplayDays.lastIndexOnOrBefore(ds, last.plusDays(10)));
    }

    @Test
    void firstIndexOnOrAfter() {
        assertEquals(0, ReplayDays.firstIndexOnOrAfter(ds, first.minusDays(10)));
        assertEquals(5, ReplayDays.firstIndexOnOrAfter(ds, first.plusDays(5)));
        assertEquals(30, ReplayDays.firstIndexOnOrAfter(ds, last.plusDays(1)));
    }
}
