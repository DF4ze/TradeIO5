package fr.ses10doigts.tradeIO5.model.entity.doctor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Trace d'une exécution de job planifié (ou de son déclenchement manuel) : alimente le Docteur (dernier run, retard,
 * échec). Append-only, purgée après {@code DoctorDefaults.JOB_RUN_RETENTION_DAYS} jours.
 */
@Entity
@Table(name = "job_run", indexes = @Index(name = "ix_job_run_job_started", columnList = "jobName,startedAt"))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRun {

    public static final int JOB_NAME_MAX = 64;
    public static final int TEXT_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = JOB_NAME_MAX, updatable = false)
    private String jobName;

    /** Colonne {@code triggered_by} : {@code trigger} est un mot réservé SQL. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private JobTrigger triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private JobRunStatus status;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    @Column(nullable = false, updatable = false)
    private long durationMs;

    /** Nombre d'éléments en erreur isolée (0 si OK ; 1 si exception). */
    @Column(nullable = false, updatable = false)
    private int errors;

    /** Résumé lisible du résultat (tronqué à {@link #TEXT_MAX}). */
    @Column(length = TEXT_MAX, updatable = false)
    private String summary;

    /** Classe + message de l'exception si {@link JobRunStatus#FAILED} (tronqué). */
    @Column(length = TEXT_MAX, updatable = false)
    private String errorMessage;
}
