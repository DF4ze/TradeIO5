package fr.ses10doigts.tradeIO5.model.enumerate.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFrameTest {

    private static final Instant T = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    @DisplayName("addTo / removeTo à l'unité sont symétriques (removeTo recule, il n'avance pas)")
    void unitStepsAreSymmetric() {
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), TimeFrame.D1.addTo(T));
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), TimeFrame.D1.removeTo(T));
        assertEquals(T, TimeFrame.H1.removeTo(TimeFrame.H1.addTo(T)));
    }

    @Test
    @DisplayName("removeTo(instant, n) recule de n périodes")
    void removeToQuantity() {
        assertEquals(Instant.parse("2024-10-23T00:00:00Z"), TimeFrame.D1.removeTo(T, 719));
        assertEquals(Instant.parse("2026-10-11T21:00:00Z"), TimeFrame.H1.removeTo(T, 3));
    }
}
