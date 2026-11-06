package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRun;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRunStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobTrigger;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Enveloppe commune des jobs : exécute le corps, trace une ligne {@code job_run} (OK / PARTIAL / FAILED) et laisse passer
 * le résultat ou l'exception telle quelle. La trace est best-effort : un échec d'écriture du journal ne casse jamais le job.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobRunRecorder {

    private final JobRunRepository repository;
    private final DomainClock clock;

    /** Cas courant : le résultat expose son nombre d'erreurs ({@code errorsOf}) et son {@code toString()} sert de résumé. */
    public <T> T run(String job, JobTrigger trigger, Supplier<T> body, ToIntFunction<T> errorsOf) {
        return execute(job, trigger, body,
                result -> result == null ? new JobOutcome(0, null) : new JobOutcome(errorsOf.applyAsInt(result), String.valueOf(result)));
    }

    /** Job sans résultat exploitable : OK s'il se termine, FAILED s'il lève. */
    public void run(String job, JobTrigger trigger, Runnable body) {
        execute(job, trigger, () -> {
            body.run();
            return null;
        }, result -> new JobOutcome(0, null));
    }

    /** Job qui calcule lui-même son résumé et son nombre d'erreurs. */
    public JobOutcome runOutcome(String job, JobTrigger trigger, Supplier<JobOutcome> body) {
        return execute(job, trigger, body, outcome -> outcome);
    }

    private <T> T execute(String job, JobTrigger trigger, Supplier<T> body, java.util.function.Function<T, JobOutcome> outcomeOf) {
        Instant start = clock.now();
        long t0 = System.nanoTime();
        T result;
        try {
            result = body.get();
        } catch (RuntimeException e) {
            save(job, trigger, start, t0, JobRunStatus.FAILED, 1, null, e);
            throw e;
        }
        JobOutcome outcome = outcomeOf.apply(result);
        save(job, trigger, start, t0, outcome.errors() > 0 ? JobRunStatus.PARTIAL : JobRunStatus.OK, outcome.errors(),
                outcome.summary(), null);
        return result;
    }

    private void save(String job, JobTrigger trigger, Instant start, long t0, JobRunStatus status, int errors,
                      String summary, RuntimeException failure) {
        long durationMs = (System.nanoTime() - t0) / 1_000_000L;
        try {
            repository.save(JobRun.builder()
                    .jobName(cut(job, JobRun.JOB_NAME_MAX))
                    .triggeredBy(trigger)
                    .status(status)
                    .startedAt(start)
                    .durationMs(durationMs)
                    .errors(errors)
                    .summary(cut(summary, JobRun.TEXT_MAX))
                    .errorMessage(failure == null ? null
                            : cut(failure.getClass().getSimpleName() + ": " + failure.getMessage(), JobRun.TEXT_MAX))
                    .build());
            log.debug("JobRunRecorder : {} {} {} en {} ms (erreurs={})", job, trigger, status, durationMs, errors);
        } catch (RuntimeException e) {
            log.warn("JobRunRecorder : trace non enregistrée pour {} ({})", job, e.toString());
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
