package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.Alarm;
import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.repository.doctor.AlarmRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cycle de vie des alarmes. {@link #reconcile} compare chaque passe du Docteur à l'état persistant : un défaut ouvre ou met à
 * jour l'alarme, un retour à OK la résout ; {@code UNKNOWN} ne touche à rien (pas de donnée pour juger). L'acquittement et la
 * mise en sommeil masquent sans résoudre ; l'alarme se rouvre si la gravité monte ou si le sommeil expire.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlarmRepository alarms;
    private final DomainClock clock;

    /**
     * @param report           verdicts de la passe
     * @param evaluatedChecks  contrôles exécutés sans exception (les alarmes des autres sont laissées telles quelles)
     */
    @Transactional
    public void reconcile(DoctorReport report, Set<String> evaluatedChecks) {
        Instant now = report.runAt();
        Set<String> seenKeys = new HashSet<>();
        for (DoctorReport.Entry entry : report.results()) {
            seenKeys.add(entry.alarmKey());
            switch (entry.status()) {
                case OK -> resolveIfOpen(entry.alarmKey(), now, "contrôle revenu OK");
                case UNKNOWN -> {
                    // pas de donnée : on ne crée ni ne résout rien
                }
                default -> upsert(entry, now);
            }
        }
        for (String checkId : evaluatedChecks) {
            for (Alarm alarm : alarms.findByCheckIdAndStateNot(checkId, AlarmState.RESOLVED)) {
                if (!seenKeys.contains(alarm.getAlarmKey())) {
                    close(alarm, now, "sujet disparu");
                }
            }
        }
    }

    private void upsert(DoctorReport.Entry entry, Instant now) {
        Optional<Alarm> existing = alarms.findFirstByAlarmKeyAndStateNotOrderByIdDesc(entry.alarmKey(), AlarmState.RESOLVED);
        if (existing.isEmpty()) {
            alarms.save(Alarm.builder().alarmKey(entry.alarmKey()).checkId(entry.checkId()).subject(entry.subject())
                    .family(entry.family()).impact(entry.impact()).severity(entry.status()).state(AlarmState.ACTIVE)
                    .message(cut(entry.message())).firstSeen(now).lastSeen(now).occurrences(1).build());
            log.info("Docteur : alarme ouverte {} [{}] {}", entry.alarmKey(), entry.status(), entry.message());
            return;
        }
        Alarm alarm = existing.get();
        boolean escalated = entry.status().severityRank() > alarm.getSeverity().severityRank();
        boolean sleepOver = alarm.getSnoozeUntil() != null && now.isAfter(alarm.getSnoozeUntil());
        if (alarm.getState() == AlarmState.ACKED && (escalated || sleepOver)) {
            alarm.setState(AlarmState.ACTIVE);
            alarm.setSnoozeUntil(null);
            log.info("Docteur : alarme rouverte {} ({})", alarm.getAlarmKey(), escalated ? "gravité en hausse" : "sommeil terminé");
        }
        alarm.setSeverity(entry.status());
        alarm.setMessage(cut(entry.message()));
        alarm.setLastSeen(now);
        alarm.setOccurrences(alarm.getOccurrences() + 1);
        alarms.save(alarm);
    }

    private void resolveIfOpen(String key, Instant now, String why) {
        alarms.findFirstByAlarmKeyAndStateNotOrderByIdDesc(key, AlarmState.RESOLVED).ifPresent(a -> close(a, now, why));
    }

    private void close(Alarm alarm, Instant now, String why) {
        alarm.setState(AlarmState.RESOLVED);
        alarm.setResolvedAt(now);
        alarms.save(alarm);
        log.info("Docteur : alarme résolue {} ({})", alarm.getAlarmKey(), why);
    }

    /** Acquitte (vue, sans échéance). {@link IllegalArgumentException} si inconnue ou déjà résolue. */
    @Transactional
    public Alarm acknowledge(Long id, String actor, String comment) {
        Alarm alarm = open(id);
        mark(alarm, actor, comment);
        alarm.setSnoozeUntil(null);
        log.info("Docteur : alarme {} acquittée par {}", alarm.getAlarmKey(), actor);
        return alarms.save(alarm);
    }

    /** Acquitte et met en sommeil {@code hours} heures (1 à {@link DoctorDefaults#SNOOZE_MAX_HOURS}). */
    @Transactional
    public Alarm snooze(Long id, String actor, int hours, String comment) {
        if (hours < 1 || hours > DoctorDefaults.SNOOZE_MAX_HOURS) {
            throw new IllegalArgumentException("durée de sommeil hors bornes (1 à " + DoctorDefaults.SNOOZE_MAX_HOURS + " h)");
        }
        Alarm alarm = open(id);
        mark(alarm, actor, comment);
        alarm.setSnoozeUntil(clock.now().plus(Duration.ofHours(hours)));
        log.info("Docteur : alarme {} en sommeil {} h par {}", alarm.getAlarmKey(), hours, actor);
        return alarms.save(alarm);
    }

    @Transactional(readOnly = true)
    public List<Alarm> list(Set<AlarmState> states) {
        return states == null || states.isEmpty() ? alarms.findAllByOrderByLastSeenDesc()
                : alarms.findByStateInOrderByLastSeenDesc(EnumSet.copyOf(states));
    }

    @Transactional(readOnly = true)
    public long countOpenBySeverity(DoctorStatus severity) {
        return alarms.findByStateInOrderByLastSeenDesc(EnumSet.of(AlarmState.ACTIVE)).stream()
                .filter(a -> a.getSeverity() == severity).count();
    }

    private Alarm open(Long id) {
        Alarm alarm = alarms.findById(id).orElseThrow(() -> new IllegalArgumentException("alarme introuvable : " + id));
        if (alarm.getState() == AlarmState.RESOLVED) {
            throw new IllegalArgumentException("alarme déjà résolue : " + id);
        }
        return alarm;
    }

    private void mark(Alarm alarm, String actor, String comment) {
        alarm.setState(AlarmState.ACKED);
        alarm.setAckedBy(cut(actor, 64));
        alarm.setAckedAt(clock.now());
        alarm.setAckComment(cut(comment, 300));
    }

    private static String cut(String value) {
        return cut(value, Alarm.TEXT_MAX);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
