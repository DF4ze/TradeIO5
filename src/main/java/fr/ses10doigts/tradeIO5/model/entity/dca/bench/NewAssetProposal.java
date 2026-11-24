package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

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
import java.time.LocalDate;

/**
 * Proposition d'accueil d'un actif sans réglage dédié (D1b) : preset de départ + taux de base par profil + drapeaux + provenance
 * de l'historique. Jamais appliquée par le calcul : seul un admin la valide (statut). Les taux sont en JSON ({@code levelsJson}) tant que
 * rien n'est validé ; la validation (lot 2) les copie dans une ligne dédiée de l'actif.
 */
@Entity
@Table(name = "new_asset_proposal", indexes = @Index(name = "ix_new_asset_proposal_asset", columnList = "asset_symbol,status"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewAssetProposal {

    public static final int FLAGS_MAX = 255;
    public static final int JSON_MAX = 4000;
    public static final int TEXT_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NewAssetProposalStatus status;

    /** Actif dont le preset (Bull / Bear + Trend par défaut du live) est proposé : BTC. */
    @Column(name = "preset_source", nullable = false, length = 16)
    private String presetSource;

    /** Étiquette source des bougies de l'étude (ex. {@code BINANCE/USDT} pour un complément bench, {@code KRAKEN/...} pour le provider du lien). */
    @Column(name = "history_source", length = 64)
    private String historySource;

    @Column(name = "candle_first_day")
    private LocalDate candleFirstDay;

    @Column(name = "candle_last_day")
    private LocalDate candleLastDay;

    @Column(name = "candle_count")
    private Integer candleCount;

    /** Empreinte des bougies ({@code CandleFingerprint}). */
    @Column(name = "candle_checksum", length = 32)
    private String candleChecksum;

    @Column(name = "history_months")
    private Integer historyMonths;

    @Column(name = "block_count")
    private Integer blockCount;

    @Column(name = "switch_count")
    private Integer switchCount;

    /** Drapeaux séparés par des virgules (PROVISOIRE, MARCHE_TROP_COURT, HISTORIQUE_INSUFFISANT, NON_TRADABLE, CALAGE_COUT_ELEVE). Jamais bloquants. */
    @Column(length = FLAGS_MAX)
    private String flags;

    /** Marge k utilisée (valeur admin au moment du calcul). */
    @Column(name = "margin_k", precision = 10, scale = 4)
    private java.math.BigDecimal marginK;

    /** Taux de base par profil (JSON : profil, plafond, multiplicateur, statut, taux/jour/cash, fenêtre du pire cas). Vide si non calculé. */
    @Column(name = "levels_json", length = JSON_MAX)
    private String levelsJson;

    @Column(name = "replays")
    private Long replays;

    @Column(name = "compute_ms")
    private Long computeMs;

    /** Avertissement visible (actif non tradable, cause d'un calage manquant…). */
    @Column(length = TEXT_MAX)
    private String warning;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;
}
