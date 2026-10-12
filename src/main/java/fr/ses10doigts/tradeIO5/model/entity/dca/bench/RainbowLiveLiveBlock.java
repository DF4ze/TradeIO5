package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Snapshot live d'un lien (binding) pour une passe : statut de la lecture du portefeuille réel, wallet, instant de lecture,
 * cash USD du pool (groupe USDC), position réelle de l'actif, position tradable ({@code bagPercent} x réelle), cash déjà
 * réservé par les liens servis avant, raison d'un blocage, et action live recommandée (plafonnée sur le réel, jamais exécutée
 * ici). Champs nullables : Hibernate lit le bloc entier comme {@code null} tant que la passe n'a pas été jouée pour le lien.
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveLiveBlock {

    @Enumerated(EnumType.STRING)
    private PortfolioStatus status;
    private Long walletId;
    private Instant fetchedAt;
    private Double cashUsd;
    /** Détail du cash par membre du groupe USD, JSON {@code {"USDC":..}}. */
    @Column(length = 255)
    private String cashDetail;
    private Double positionQty;
    private Double tradableQty;
    private Double cashReserved;
    @Enumerated(EnumType.STRING)
    private LiveBlockReason blockReason;

    @Enumerated(EnumType.STRING)
    private RainbowLiveAction actionType;
    private Double actionAmountUsdc;
    private Double actionQuantity;
    /** Exchange dont viennent les candles D1 du signal de ce lien ({@code null} = lecture antérieure à ce champ). */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private MarketDataSource candleSource;
    /** Vrai si la source du lien était indisponible et que le signal vient d'un AUTRE exchange (fallback, WARN). */
    private Boolean candleSourceSubstituted;

    /** Vrai si le bloc porte un snapshot (une lecture a été tentée pour cette passe). */
    public boolean hasLiveSnapshot() {
        return status != null;
    }
}
