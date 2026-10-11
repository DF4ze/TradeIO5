package fr.ses10doigts.tradeIO5.model.entity.admin;

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
 * Ligne du journal des actions admin : <b>append-only</b> (aucun setter, colonnes non modifiables, repository sans
 * suppression ni mise à jour). {@code payload} = JSON texte choisi par l'appelant, jamais un secret.
 */
@Entity
@Table(name = "admin_action_log", indexes = @Index(name = "ix_admin_action_log_created_at", columnList = "createdAt"))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminActionLog {

    public static final int ACTOR_MAX = 64;
    public static final int TARGET_MAX = 120;
    public static final int PAYLOAD_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom d'utilisateur de l'auteur (ou {@code auto-reengage} pour le réengagement automatique). */
    @Column(nullable = false, length = ACTOR_MAX, updatable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private AdminAction action;

    /** Cible courte : planId, bindingId, credentialId... */
    @Column(length = TARGET_MAX, updatable = false)
    private String target;

    @Column(length = PAYLOAD_MAX, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private AdminActionOutcome outcome;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
