package fr.ses10doigts.tradeIO5.model.dto.admin;

import fr.ses10doigts.tradeIO5.model.entity.doctor.Alarm;
import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRun;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRunStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobTrigger;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorReport;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;

/** DTO de l'API Admin du Docteur. Aucun secret. */
public final class DoctorDtos {

    private DoctorDtos() {
    }

    /** Dernier rapport + nombre d'alarmes ouvertes (non acquittées) par gravité. */
    public record Report(Instant runAt, DoctorStatus worst, long activeCrit, long activeWarn,
                         List<DoctorReport.Entry> results) {
    }

    public record AlarmView(Long id, String checkId, String subject, DoctorFamily family, DoctorImpact impact,
                            DoctorStatus severity, AlarmState state, String message, Instant firstSeen, Instant lastSeen,
                            int occurrences, Instant resolvedAt, String ackedBy, Instant ackedAt, String ackComment,
                            Instant snoozeUntil) {
        public static AlarmView of(Alarm a) {
            return new AlarmView(a.getId(), a.getCheckId(), a.getSubject(), a.getFamily(), a.getImpact(), a.getSeverity(),
                    a.getState(), a.getMessage(), a.getFirstSeen(), a.getLastSeen(), a.getOccurrences(), a.getResolvedAt(),
                    a.getAckedBy(), a.getAckedAt(), a.getAckComment(), a.getSnoozeUntil());
        }
    }

    public record JobRunView(Long id, String jobName, JobTrigger trigger, JobRunStatus status, Instant startedAt,
                             long durationMs, int errors, String summary, String errorMessage) {
        public static JobRunView of(JobRun r) {
            return new JobRunView(r.getId(), r.getJobName(), r.getTriggeredBy(), r.getStatus(), r.getStartedAt(),
                    r.getDurationMs(), r.getErrors(), r.getSummary(), r.getErrorMessage());
        }
    }

    public record JobRunPage(List<JobRunView> items, int page, int size, long total) {
        public static JobRunPage of(Page<JobRun> page) {
            return new JobRunPage(page.getContent().stream().map(JobRunView::of).toList(), page.getNumber(), page.getSize(),
                    page.getTotalElements());
        }
    }

    public record AckRequest(String comment) {
    }

    public record SnoozeRequest(int hours, String comment) {
    }
}
