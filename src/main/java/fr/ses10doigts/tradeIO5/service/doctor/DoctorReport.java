package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;

import java.time.Instant;
import java.util.List;

/** Photo d'une passe du Docteur. {@code worst} = pire statut parmi les verdicts ({@code UNKNOWN} ignoré s'il y a mieux à dire). */
public record DoctorReport(Instant runAt, DoctorStatus worst, List<Entry> results) {

    /** Verdict d'un contrôle pour un sujet, enrichi des métadonnées du contrôle. */
    public record Entry(String checkId, String label, DoctorFamily family, DoctorImpact impact, String subject,
                        DoctorStatus status, String message) {

        public String alarmKey() {
            return checkId + ":" + subject;
        }
    }
}
