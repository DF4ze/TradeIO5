package fr.ses10doigts.tradeIO5.service.doctor.check;

import ch.qos.logback.classic.Level;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorLogBuffer;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LogErrorsCheck / DoctorLogBuffer")
class LogErrorsCheckTest {

    private final DoctorLogBuffer buffer = new DoctorLogBuffer();
    private final FixedDomainClock clock = new FixedDomainClock(Instant.now().plusSeconds(60));
    private final LogErrorsCheck check = new LogErrorsCheck(buffer, clock);

    private DoctorStatus status(List<CheckResult> results, String subject) {
        return results.stream().filter(r -> r.subject().equals(subject)).findFirst().orElseThrow().status();
    }

    @Test
    @DisplayName("aucun log => ERROR et WARN en OK")
    void none() {
        List<CheckResult> r = check.run();
        assertEquals(DoctorStatus.OK, status(r, "ERROR"));
        assertEquals(DoctorStatus.OK, status(r, "WARN"));
    }

    @Test
    @DisplayName("un seul ERROR suffit à alarmer, avec le dernier échantillon")
    void oneErrorAlarms() {
        buffer.record(new DoctorLogBuffer.Entry(Instant.now(), Level.ERROR, "f.s.Foo", "échec du truc"));
        List<CheckResult> r = check.run();
        assertEquals(DoctorStatus.WARN, status(r, "ERROR"));
        assertTrue(r.stream().filter(x -> x.subject().equals("ERROR")).findFirst().orElseThrow().message().contains("échec du truc"));
    }

    @Test
    @DisplayName("les WARN n'alarment qu'au seuil")
    void warnThreshold() {
        for (int i = 0; i < DoctorDefaults.LOG_WARN_THRESHOLD - 1; i++) {
            buffer.record(new DoctorLogBuffer.Entry(Instant.now(), Level.WARN, "l", "w"));
        }
        assertEquals(DoctorStatus.OK, status(check.run(), "WARN"));
        buffer.record(new DoctorLogBuffer.Entry(Instant.now(), Level.WARN, "l", "w"));
        assertEquals(DoctorStatus.WARN, status(check.run(), "WARN"));
    }

    @Test
    @DisplayName("les logs plus vieux que la fenêtre de 24 h sont ignorés")
    void oldIgnored() {
        buffer.record(new DoctorLogBuffer.Entry(Instant.now().minus(Duration.ofHours(30)), Level.ERROR, "l", "vieux"));
        clock.advance(Duration.ofHours(30));
        assertEquals(DoctorStatus.OK, status(check.run(), "ERROR"));
    }

    @Test
    @DisplayName("tampon borné")
    void bounded() {
        for (int i = 0; i < DoctorDefaults.LOG_BUFFER_MAX + 50; i++) {
            buffer.record(new DoctorLogBuffer.Entry(Instant.now(), Level.WARN, "l", "w" + i));
        }
        assertEquals(DoctorDefaults.LOG_BUFFER_MAX, buffer.since(Instant.EPOCH, Level.WARN).size());
    }
}
