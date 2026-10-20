package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Prochaine passe : cron 23:55 UTC")
class NextPassServiceTest {

    @Test
    @DisplayName("Avant 23:55 : le jour même ; après : le lendemain")
    void nextRun() {
        var before = new NextPassService("0 55 23 * * *", new FixedDomainClock(Instant.parse("2026-10-20T10:00:00Z"))).next();
        var after = new NextPassService("0 55 23 * * *", new FixedDomainClock(Instant.parse("2026-10-20T23:56:00Z"))).next();

        assertTrue(before.enabled());
        assertEquals(Instant.parse("2026-10-20T23:55:00Z"), before.nextRunAt());
        assertEquals(Instant.parse("2026-10-21T23:55:00Z"), after.nextRunAt());
    }

    @Test
    @DisplayName("Cron désactivé (« - ») : pas de date")
    void disabled() {
        var next = new NextPassService("-", new FixedDomainClock(Instant.parse("2026-10-20T10:00:00Z"))).next();

        assertFalse(next.enabled());
        assertNull(next.nextRunAt());
    }
}
