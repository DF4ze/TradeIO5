package fr.ses10doigts.tradeIO5.model.entity.market;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Catalogue des paires spot d'un exchange (donnée publique partagée, sans utilisateur), alimenté par un seul appel
 * « toutes les paires » par exchange et rafraîchi au plus une fois par jour
 * ({@link fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog}). Tailles en unités de la base.
 */
@Entity
@Table(name = "exchange_instrument",
        uniqueConstraints = @UniqueConstraint(name = "uk_exchange_instrument_provider_inst",
                columnNames = {"provider", "inst_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeInstrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WebProviderCode provider;

    /** Identifiant normalisé {@code BASE-QUOTE} (symboles standard : BTC, USDC...). */
    @Column(name = "inst_id", nullable = false, length = 40)
    private String instId;

    @Column(nullable = false, length = 20)
    private String base;

    @Column(nullable = false, length = 20)
    private String quote;

    /** {@code live} = négociable (valeur normalisée pour tous les exchanges). */
    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "min_sz", nullable = false, precision = 38, scale = 18)
    private BigDecimal minSz;

    @Column(name = "lot_sz", nullable = false, precision = 38, scale = 18)
    private BigDecimal lotSz;

    @Column(name = "tick_sz", nullable = false, precision = 38, scale = 18)
    private BigDecimal tickSz;

    /** Coût minimum d'un ordre en cotation (Kraken {@code costmin}) ; nul si l'exchange n'en porte pas (OKX). */
    @Column(name = "cost_min", precision = 38, scale = 18)
    private BigDecimal costMin;

    /** Nom de la paire dans l'API de trading (Kraken : {@code XBTUSDC}) ; nul si c'est {@code instId} (OKX). */
    @Column(name = "native_pair", length = 40)
    private String nativePair;

    /** Devise des remises de frais au volume ({@code fee_volume_currency}) ; nulle si inconnue. */
    @Column(name = "fee_currency", length = 20)
    private String feeCurrency;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
