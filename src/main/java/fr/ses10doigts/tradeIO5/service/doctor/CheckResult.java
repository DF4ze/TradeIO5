package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;

/** Verdict d'un contrôle pour un sujet donné (un job, un plan, un disque…). */
public record CheckResult(String subject, DoctorStatus status, String message) {

    public static CheckResult ok(String subject, String message) {
        return new CheckResult(subject, DoctorStatus.OK, message);
    }

    public static CheckResult unknown(String subject, String message) {
        return new CheckResult(subject, DoctorStatus.UNKNOWN, message);
    }

    public static CheckResult warn(String subject, String message) {
        return new CheckResult(subject, DoctorStatus.WARN, message);
    }

    public static CheckResult crit(String subject, String message) {
        return new CheckResult(subject, DoctorStatus.CRIT, message);
    }
}
