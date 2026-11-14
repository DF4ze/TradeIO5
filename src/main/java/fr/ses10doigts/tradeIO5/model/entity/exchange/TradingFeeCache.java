package fr.ses10doigts.tradeIO5.model.entity.exchange;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Frais réels du compte, mis en cache (durée de vie {@code ExecutionDefaults.FEE_CACHE_TTL}). Clé = (credential, instrument),
 * ou (credential, {@link #ACCOUNT_WIDE}) pour un palier valable pour tout le compte. Jamais de clé API ni de secret ici.
 * Supprimé en cascade (FK) avec le credential.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "trading_fee_cache",
        uniqueConstraints = @UniqueConstraint(name = "uk_trading_fee_cache_cred_inst", columnNames = {"credential_id", "inst_key"}))
public class TradingFeeCache {

    /** Clé d'instrument d'un palier valable pour tout le compte. */
    public static final String ACCOUNT_WIDE = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credential_id", nullable = false)
    private Long credentialId;

    @Column(name = "inst_key", nullable = false, length = 64)
    private String instKey;

    @Column(name = "maker_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal makerPct;

    @Column(name = "taker_pct", nullable = false, precision = 19, scale = 8)
    private BigDecimal takerPct;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
