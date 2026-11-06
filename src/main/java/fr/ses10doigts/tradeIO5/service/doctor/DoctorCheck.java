package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;

import java.util.List;

/**
 * Sonde du Docteur : lecture seule, sans effet de bord. Un contrôle rend un verdict par sujet (ex. un par job, un par plan) ;
 * un sujet qui disparaît d'une passe à l'autre résout son alarme. Tout bean {@code DoctorCheck} est exécuté par
 * {@link DoctorService}.
 */
public interface DoctorCheck {

    /** Identifiant stable (clé d'alarme, ex. {@code JOB_SCHEDULE}). */
    String id();

    String label();

    DoctorFamily family();

    DoctorImpact impact();

    List<CheckResult> run();
}
