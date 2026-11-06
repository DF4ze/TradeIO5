package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("DoctorService")
class DoctorServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private static DoctorCheck check(String id, List<CheckResult> results) {
        return new DoctorCheck() {
            public String id() { return id; }
            public String label() { return "l-" + id; }
            public DoctorFamily family() { return DoctorFamily.INFRA; }
            public DoctorImpact impact() { return DoctorImpact.QUALITY; }
            public List<CheckResult> run() { return results; }
        };
    }

    private static DoctorCheck broken(String id) {
        return new DoctorCheck() {
            public String id() { return id; }
            public String label() { return "l-" + id; }
            public DoctorFamily family() { return DoctorFamily.INFRA; }
            public DoctorImpact impact() { return DoctorImpact.QUALITY; }
            public List<CheckResult> run() { throw new IllegalStateException("boum"); }
        };
    }

    @Test
    @DisplayName("un contrôle qui plante devient un verdict WARN et n'arrête pas les autres ; ses alarmes ne sont pas touchées")
    void isolatesFailingCheck() {
        AlarmService alarms = mock(AlarmService.class);
        DoctorService doctor = new DoctorService(List.of(broken("B"), check("G", List.of(CheckResult.ok("x", "ok")))),
                alarms, new FixedDomainClock(NOW));

        DoctorReport report = doctor.runAll();

        assertEquals(2, report.results().size());
        DoctorReport.Entry failed = report.results().stream().filter(e -> e.checkId().equals("B")).findFirst().orElseThrow();
        assertEquals(DoctorStatus.WARN, failed.status());
        assertTrue(failed.message().contains("boum"));
        assertEquals(DoctorStatus.WARN, report.worst());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> evaluated = ArgumentCaptor.forClass(Set.class);
        verify(alarms).reconcile(any(), evaluated.capture());
        assertEquals(Set.of("G"), evaluated.getValue());
    }

    @Test
    @DisplayName("pire statut : CRIT > WARN > UNKNOWN > OK")
    void worstStatus() {
        AlarmService alarms = mock(AlarmService.class);
        assertEquals(DoctorStatus.OK, new DoctorService(List.of(check("A", List.of(CheckResult.ok("s", "")))), alarms,
                new FixedDomainClock(NOW)).runAll().worst());
        assertEquals(DoctorStatus.UNKNOWN, new DoctorService(List.of(check("A", List.of(CheckResult.ok("s", ""),
                CheckResult.unknown("t", "")))), alarms, new FixedDomainClock(NOW)).runAll().worst());
        assertEquals(DoctorStatus.CRIT, new DoctorService(List.of(check("A", List.of(CheckResult.warn("s", ""),
                CheckResult.crit("t", ""), CheckResult.unknown("u", "")))), alarms, new FixedDomainClock(NOW)).runAll().worst());
    }

    @Test
    @DisplayName("report() lance une première passe puis réutilise la dernière")
    void reportCaches() {
        AlarmService alarms = mock(AlarmService.class);
        DoctorService doctor = new DoctorService(List.of(check("A", List.of(CheckResult.ok("s", "")))), alarms,
                new FixedDomainClock(NOW));
        DoctorReport first = doctor.report();
        assertSame(first, doctor.report());
        verify(alarms, times(1)).reconcile(any(), any());
    }
}
