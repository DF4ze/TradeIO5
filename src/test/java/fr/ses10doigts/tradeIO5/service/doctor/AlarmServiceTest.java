package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.Alarm;
import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.repository.doctor.AlarmRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@DisplayName("AlarmService")
class AlarmServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    @Autowired
    private AlarmRepository repository;

    private FixedDomainClock clock;
    private AlarmService service;

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(T0);
        service = new AlarmService(repository, clock);
    }

    private DoctorReport.Entry entry(String check, String subject, DoctorStatus status, String message) {
        return new DoctorReport.Entry(check, "label", DoctorFamily.PLANNING, DoctorImpact.EXECUTION, subject, status, message);
    }

    private void pass(Set<String> evaluated, DoctorReport.Entry... entries) {
        service.reconcile(new DoctorReport(clock.now(), DoctorStatus.OK, List.of(entries)), evaluated);
    }

    private List<Alarm> all() {
        return repository.findAllByOrderByLastSeenDesc();
    }

    @Test
    @DisplayName("un défaut ouvre une alarme unique, une 2e passe la met à jour sans doublon")
    void opensThenUpdates() {
        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m1"));
        clock.advance(Duration.ofMinutes(5));
        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m2"));

        assertEquals(1, all().size());
        Alarm a = all().getFirst();
        assertEquals(AlarmState.ACTIVE, a.getState());
        assertEquals(2, a.getOccurrences());
        assertEquals("m2", a.getMessage());
        assertEquals(T0, a.getFirstSeen());
        assertEquals(T0.plus(Duration.ofMinutes(5)), a.getLastSeen());
    }

    @Test
    @DisplayName("acquittée : reste acquittée à gravité égale, se rouvre si la gravité monte")
    void ackThenEscalation() {
        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m"));
        Long id = all().getFirst().getId();
        service.acknowledge(id, "clem", "vu");

        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m"));
        assertEquals(AlarmState.ACKED, repository.findById(id).orElseThrow().getState());
        assertEquals("clem", repository.findById(id).orElseThrow().getAckedBy());

        pass(Set.of("C"), entry("C", "s", DoctorStatus.CRIT, "pire"));
        Alarm a = repository.findById(id).orElseThrow();
        assertEquals(AlarmState.ACTIVE, a.getState());
        assertEquals(DoctorStatus.CRIT, a.getSeverity());
    }

    @Test
    @DisplayName("retour à OK : résolue ; une récidive ouvre une NOUVELLE alarme")
    void resolvedThenRecurrence() {
        pass(Set.of("C"), entry("C", "s", DoctorStatus.CRIT, "m"));
        Long first = all().getFirst().getId();
        pass(Set.of("C"), entry("C", "s", DoctorStatus.OK, "ok"));
        assertEquals(AlarmState.RESOLVED, repository.findById(first).orElseThrow().getState());

        pass(Set.of("C"), entry("C", "s", DoctorStatus.CRIT, "encore"));
        assertEquals(2, all().size());
        Alarm open = repository.findFirstByAlarmKeyAndStateNotOrderByIdDesc("C:s", AlarmState.RESOLVED).orElseThrow();
        assertNotEquals(first, open.getId());
    }

    @Test
    @DisplayName("UNKNOWN ne crée ni ne résout rien")
    void unknownTouchesNothing() {
        pass(Set.of("C"), entry("C", "s", DoctorStatus.UNKNOWN, "?"));
        assertEquals(0, all().size());

        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m"));
        pass(Set.of("C"), entry("C", "s", DoctorStatus.UNKNOWN, "?"));
        assertEquals(AlarmState.ACTIVE, all().getFirst().getState());
    }

    @Test
    @DisplayName("sujet disparu : alarme résolue si le contrôle a été évalué, laissée si le contrôle a planté")
    void disappearedSubject() {
        pass(Set.of("C"), entry("C", "plan#1", DoctorStatus.WARN, "m"));

        pass(Set.of());
        assertEquals(AlarmState.ACTIVE, all().getFirst().getState());

        pass(Set.of("C"));
        assertEquals(AlarmState.RESOLVED, all().getFirst().getState());
    }

    @Test
    @DisplayName("sommeil : masque jusqu'à l'échéance puis rouvre si le défaut persiste")
    void snoozeExpires() {
        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m"));
        Long id = all().getFirst().getId();
        service.snooze(id, "clem", 2, "connu");
        assertEquals(T0.plus(Duration.ofHours(2)), repository.findById(id).orElseThrow().getSnoozeUntil());

        clock.advance(Duration.ofHours(1));
        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m"));
        assertEquals(AlarmState.ACKED, repository.findById(id).orElseThrow().getState());

        clock.advance(Duration.ofHours(2));
        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m"));
        Alarm a = repository.findById(id).orElseThrow();
        assertEquals(AlarmState.ACTIVE, a.getState());
        assertNull(a.getSnoozeUntil());
    }

    @Test
    @DisplayName("garde-fous : durée de sommeil bornée, alarme inconnue ou résolue refusée")
    void guards() {
        pass(Set.of("C"), entry("C", "s", DoctorStatus.WARN, "m"));
        Long id = all().getFirst().getId();
        assertThrows(IllegalArgumentException.class, () -> service.snooze(id, "clem", 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.snooze(id, "clem", DoctorDefaults.SNOOZE_MAX_HOURS + 1, null));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(999_999L, "clem", null));

        pass(Set.of("C"), entry("C", "s", DoctorStatus.OK, "ok"));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(id, "clem", null));
    }
}
