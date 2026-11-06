package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;

import java.time.Instant;

import static org.mockito.Mockito.mock;

/** Fabrique de {@link JobRunRecorder} pour les tests de jobs/services qui n'assertent pas sur le journal. */
public final class JobRunRecorders {

    private JobRunRecorders() {
    }

    /** Recorder réel adossé à un repository mocké (ne persiste rien, laisse passer résultat et exceptions). */
    public static JobRunRecorder noop() {
        return new JobRunRecorder(mock(JobRunRepository.class), Instant::now);
    }
}
