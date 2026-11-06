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
import lombok.Setter;

import java.time.Instant;

/**
 * Alarme du Docteur : état persistant dérivé d'un contrôle qui sort de OK. Clé de dédoublonnage
 * {@code alarmKey = checkId:subject} (une seule alarme non résolue par clé). Seul le retour du contrôle à OK la résout :
 * l'acquittement (éventuellement en sommeil jusqu'à {@code snoozeUntil}) ne fait que la masquer, et elle se rouvre si la
 * gravité monte ou si le sommeil expire.
 */
@Entity
@Table(name = "doctor_alarm", indexes = {
        @Index(name = "ix_doctor_alarm_key_state", columnList = "alarmKey,state"),
        @Index(name = "ix_doctor_alarm_state", columnList = "state")})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Alarm {

    public static final int KEY_MAX = 200;
    public static final int TEXT_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = KEY_MAX)
    private String alarmKey;

    @Column(nullable = false, length = 48)
    private String checkId;

    @Column(nullable = false, length = 140)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DoctorFamily family;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DoctorImpact impact;

    /** WARN ou CRIT. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private DoctorStatus severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private AlarmState state;

    @Column(length = TEXT_MAX)
    private String message;

    @Column(nullable = false)
    private Instant firstSeen;

    @Column(nullable = false)
    private Instant lastSeen;

    /** Nombre de passes du Docteur où le contrôle était encore en défaut. */
    @Column(nullable = false)
    private int occurrences;

    private Instant resolvedAt;

    @Column(length = 64)
    private String ackedBy;

    private Instant ackedAt;

    @Column(length = 300)
    private String ackComment;

    /** Fin de mise en sommeil (nul : acquittement sans échéance). */
    private Instant snoozeUntil;
}
