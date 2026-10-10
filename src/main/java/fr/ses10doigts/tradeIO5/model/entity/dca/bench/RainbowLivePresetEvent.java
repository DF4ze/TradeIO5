package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Historique des switchs de preset d'un utilisateur (activation, détachement, bascule du preset live, changement de
 * stratégie subi). Presets avant/après référencés par id et nom, sans FK : l'historique survit à la suppression d'un preset. Écrit uniquement par
 * {@code RainbowLivePresetEventService}.
 */
@Entity
@Table(name = "rainbow_live_preset_event",
        indexes = @Index(name = "idx_rainbow_live_preset_event_user_at", columnList = "user_id, occurred_at"))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLivePresetEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RainbowLivePresetEventType type;

    /** Id du preset avant (sans FK : l'historique survit à la suppression du preset). */
    private Long presetBeforeId;

    @Column(length = 96)
    private String presetBeforeName;

    private Long presetAfterId;

    @Column(length = 96)
    private String presetAfterName;

    /** Révision de la stratégie concernée (STRATEGY_CHANGED, DETACH), sinon null. */
    private Integer strategyRevision;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(length = 255)
    private String reason;
}
