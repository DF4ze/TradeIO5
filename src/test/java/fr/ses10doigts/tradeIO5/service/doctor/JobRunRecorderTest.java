package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRun;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRunStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobTrigger;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("JobRunRecorder")
class JobRunRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-06T23:55:00Z");

    private JobRunRepository repository;
    private JobRunRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(JobRunRepository.class);
        recorder = new JobRunRecorder(repository, new FixedDomainClock(NOW));
    }

    private JobRun saved() {
        ArgumentCaptor<JobRun> captor = ArgumentCaptor.forClass(JobRun.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("0 erreur => OK, résultat rendu tel quel, résumé = toString")
    void ok() {
        Object result = new Object() {
            @Override
            public String toString() {
                return "résumé";
            }
        };
        assertSame(result, recorder.run("J", JobTrigger.CRON, () -> result, r -> 0));
        JobRun run = saved();
        assertEquals(JobRunStatus.OK, run.getStatus());
        assertEquals(JobTrigger.CRON, run.getTriggeredBy());
        assertEquals(NOW, run.getStartedAt());
        assertEquals("résumé", run.getSummary());
        assertEquals(0, run.getErrors());
    }

    @Test
    @DisplayName("erreurs isolées > 0 => PARTIAL")
    void partial() {
        recorder.run("J", JobTrigger.MANUAL, () -> "x", r -> 2);
        JobRun run = saved();
        assertEquals(JobRunStatus.PARTIAL, run.getStatus());
        assertEquals(JobTrigger.MANUAL, run.getTriggeredBy());
        assertEquals(2, run.getErrors());
    }

    @Test
    @DisplayName("exception => FAILED tracé puis relancée à l'identique")
    void failed() {
        IllegalStateException boom = new IllegalStateException("boum");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> recorder.run("J", JobTrigger.CRON, () -> {
                    throw boom;
                }, r -> 0));
        assertSame(boom, thrown);
        JobRun run = saved();
        assertEquals(JobRunStatus.FAILED, run.getStatus());
        assertEquals("IllegalStateException: boum", run.getErrorMessage());
    }

    @Test
    @DisplayName("résultat nul (mock non stubbé) => OK sans NPE")
    void nullResult() {
        assertNull(recorder.run("J", JobTrigger.CRON, () -> null, Object::hashCode));
        assertEquals(JobRunStatus.OK, saved().getStatus());
    }

    @Test
    @DisplayName("Runnable : OK sans résumé")
    void runnable() {
        recorder.run("J", JobTrigger.CRON, () -> {
        });
        JobRun run = saved();
        assertEquals(JobRunStatus.OK, run.getStatus());
        assertNull(run.getSummary());
    }

    @Test
    @DisplayName("runOutcome : erreurs et résumé fournis par le job")
    void outcome() {
        recorder.runOutcome("J", JobTrigger.CRON, () -> new JobOutcome(1, "1 source en erreur"));
        JobRun run = saved();
        assertEquals(JobRunStatus.PARTIAL, run.getStatus());
        assertEquals("1 source en erreur", run.getSummary());
    }

    @Test
    @DisplayName("résumé tronqué à TEXT_MAX")
    void summaryTruncated() {
        recorder.run("J", JobTrigger.CRON, () -> "a".repeat(JobRun.TEXT_MAX + 100), r -> 0);
        assertEquals(JobRun.TEXT_MAX, saved().getSummary().length());
    }

    @Test
    @DisplayName("échec d'écriture du journal : le job n'est pas affecté")
    void journalFailureDoesNotBreakJob() {
        doThrow(new RuntimeException("db down")).when(repository).save(any());
        assertEquals("ok", recorder.run("J", JobTrigger.CRON, () -> "ok", r -> 0));
        assertTrue(true);
    }
}
