package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import fr.ses10doigts.tradeIO5.repository.doctor.AlarmRepository;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("JobRunPurgeJob")
class JobRunPurgeJobTest {

    @Test
    @DisplayName("purge les traces de jobs et les alarmes résolues au-delà de la rétention")
    void purgesBeyondRetention() {
        Instant now = Instant.parse("2026-11-06T03:30:00Z");
        JobRunRepository runs = mock(JobRunRepository.class);
        AlarmRepository alarms = mock(AlarmRepository.class);

        new JobRunPurgeJob(runs, alarms, new FixedDomainClock(now)).purge();

        Instant cutoff = now.minus(Duration.ofDays(DoctorDefaults.JOB_RUN_RETENTION_DAYS));
        verify(runs).deleteByStartedAtBefore(cutoff);
        verify(alarms).deleteByStateAndResolvedAtBefore(AlarmState.RESOLVED, cutoff);
    }
}
