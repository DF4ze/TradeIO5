package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRun;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRunStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobTrigger;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JobScheduleCheck")
class JobScheduleCheckTest {

    private static final String JOB = DoctorDefaults.JOB_RAINBOW_PASS;
    /** Échéance 23:55 UTC du 5/11 ; « maintenant » = 6/11 00:30 UTC. */
    private static final Instant DUE = Instant.parse("2026-11-05T23:55:00Z");
    private static final Instant NOW = Instant.parse("2026-11-06T00:30:00Z");

    private MockEnvironment env;
    private JobRunRepository runs;
    private FixedDomainClock clock;
    private JobScheduleCheck check;

    @BeforeEach
    void setUp() {
        env = new MockEnvironment()
                .withProperty(DoctorDefaults.EXPECTED_JOBS_PROPERTY, JOB)
                .withProperty(RainbowLiveDefaultPresets.PASS_2355_CRON_PROPERTY, "0 55 23 * * *");
        runs = mock(JobRunRepository.class);
        clock = new FixedDomainClock(NOW);
        check = new JobScheduleCheck(env, runs, clock);
    }

    private void lastRun(Instant startedAt, JobRunStatus status, int errors) {
        when(runs.findFirstByJobNameOrderByStartedAtDesc(JOB)).thenReturn(Optional.of(JobRun.builder().jobName(JOB)
                .triggeredBy(JobTrigger.CRON).status(status).startedAt(startedAt).durationMs(120).errors(errors)
                .summary("PassSummary[...]").errorMessage(status == JobRunStatus.FAILED ? "IllegalStateException: x" : null).build()));
    }

    private CheckResult only() {
        List<CheckResult> results = check.run();
        assertEquals(1, results.size());
        assertEquals(JOB, results.getFirst().subject());
        return results.getFirst();
    }

    @Test
    @DisplayName("cron désactivé alors que le job est attendu => CRIT")
    void cronDisabled() {
        env.setProperty(RainbowLiveDefaultPresets.PASS_2355_CRON_PROPERTY, "-");
        assertEquals(DoctorStatus.CRIT, only().status());
        assertEquals(DoctorStatus.CRIT, new JobScheduleCheck(new MockEnvironment()
                .withProperty(DoctorDefaults.EXPECTED_JOBS_PROPERTY, JOB), runs, clock).run().getFirst().status());
    }

    @Test
    @DisplayName("cron invalide => CRIT")
    void cronInvalid() {
        env.setProperty(RainbowLiveDefaultPresets.PASS_2355_CRON_PROPERTY, "n'importe quoi");
        assertEquals(DoctorStatus.CRIT, only().status());
    }

    @Test
    @DisplayName("journal vide => UNKNOWN (jamais une fausse alarme au premier déploiement)")
    void noRunYet() {
        when(runs.findFirstByJobNameOrderByStartedAtDesc(JOB)).thenReturn(Optional.empty());
        assertEquals(DoctorStatus.UNKNOWN, only().status());
    }

    @Test
    @DisplayName("run à l'heure et OK => OK")
    void onTimeOk() {
        lastRun(DUE.plusMillis(500), JobRunStatus.OK, 0);
        CheckResult r = only();
        assertEquals(DoctorStatus.OK, r.status());
        assertTrue(r.message().contains("PassSummary"));
    }

    @Test
    @DisplayName("run à l'heure partiel => WARN ; en échec => CRIT")
    void partialAndFailed() {
        lastRun(DUE.plusSeconds(1), JobRunStatus.PARTIAL, 2);
        assertEquals(DoctorStatus.WARN, only().status());
        lastRun(DUE.plusSeconds(1), JobRunStatus.FAILED, 1);
        assertEquals(DoctorStatus.CRIT, only().status());
    }

    @Test
    @DisplayName("aucune exécution depuis l'échéance, grâce dépassée => CRIT « en retard »")
    void late() {
        lastRun(Instant.parse("2026-11-04T23:55:00Z"), JobRunStatus.OK, 0);
        CheckResult r = only();
        assertEquals(DoctorStatus.CRIT, r.status());
        assertTrue(r.message().startsWith("en retard"));
    }

    @Test
    @DisplayName("échéance toute récente (dans le délai de grâce) => OK, pas d'alarme prématurée")
    void withinGrace() {
        clock.advance(java.time.Duration.ofMinutes(-25));
        lastRun(Instant.parse("2026-11-04T23:55:00Z"), JobRunStatus.OK, 0);
        assertEquals(DoctorStatus.OK, only().status());
    }

    @Test
    @DisplayName("un relancement manuel après une panne rattrape l'échéance manquée")
    void manualCatchUp() {
        lastRun(DUE.plus(java.time.Duration.ofMinutes(20)), JobRunStatus.OK, 0);
        assertEquals(DoctorStatus.OK, only().status());
    }

    @Test
    @DisplayName("job inconnu du Docteur => UNKNOWN")
    void unknownJob() {
        env.setProperty(DoctorDefaults.EXPECTED_JOBS_PROPERTY, "JobInconnu");
        assertEquals(DoctorStatus.UNKNOWN, check.run().getFirst().status());
    }

    @Test
    @DisplayName("previousFiring : dernière échéance ≤ maintenant, fuseau pris en compte")
    void previousFiring() {
        CronExpression cron = CronExpression.parse("0 55 23 * * *");
        assertEquals(DUE, JobScheduleCheck.previousFiring(cron, ZoneId.of("UTC"), NOW));
        assertEquals(DUE.minus(java.time.Duration.ofDays(1)),
                JobScheduleCheck.previousFiring(cron, ZoneId.of("UTC"), Instant.parse("2026-11-05T23:54:59Z")));
    }
}
