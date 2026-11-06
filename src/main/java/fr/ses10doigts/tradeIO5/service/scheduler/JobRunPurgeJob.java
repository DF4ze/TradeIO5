package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import fr.ses10doigts.tradeIO5.repository.doctor.AlarmRepository;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Purge quotidienne du journal des jobs et des alarmes résolues au-delà de {@link DoctorDefaults#JOB_RUN_RETENTION_DAYS} jours. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobRunPurgeJob {

    private final JobRunRepository repository;
    private final AlarmRepository alarms;
    private final DomainClock clock;

    @Scheduled(cron = DoctorDefaults.PURGE_CRON)
    @Transactional
    public void purge() {
        Instant cutoff = clock.now().minus(DoctorDefaults.JOB_RUN_RETENTION_DAYS, ChronoUnit.DAYS);
        long deleted = repository.deleteByStartedAtBefore(cutoff);
        long resolved = alarms.deleteByStateAndResolvedAtBefore(AlarmState.RESOLVED, cutoff);
        log.info("JobRunPurgeJob : {} trace(s) de job et {} alarme(s) résolue(s) supprimée(s)", deleted, resolved);
    }
}
