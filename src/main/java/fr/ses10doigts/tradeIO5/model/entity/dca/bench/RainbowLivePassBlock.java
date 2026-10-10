package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Résultat d'une passe (23:55 ou 00:05) : indicateurs, états des machines, action. Champs nullables
 * (Hibernate lit le bloc entier comme {@code null} tant que la passe n'a pas été jouée).
 * <p>
 * {@code cashAfter}/{@code positionAfter} : état du wallet mock après l'action — renseignés par
 * {@code RainbowLiveRunService} pour la seule passe 23:55 (nuls en 00:05 : « action qui aurait été
 * prise », jamais appliquée).
 */
@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLivePassBlock {

    private Double close;
    private Double sma;
    private Double atr;
    /** Bornes ATR : SMA − ATR × atrMultDown2 (EXTREME_BAS). */
    private Double boundDown2;
    /** SMA − ATR × atrMultDown1. */
    private Double boundDown1;
    /** SMA + ATR × atrMultUp1. */
    private Double boundUp1;
    /** SMA + ATR × atrMultUp2. */
    private Double boundUp2;
    /** SMA + ATR × atrMultUp3 (EXTREME_HAUT). */
    private Double boundUp3;
    /** Index de zone de {@code RainbowAtrEngine} (EXTREME_BAS=0 … EXTREME_HAUT=5). */
    private Integer zone;
    private Double athDistance;
    private Double buyFactor;
    private Double sellFactor;
    private Boolean moonMode;
    private Boolean buyArmed;
    private Boolean sellArmed;
    private Boolean buyLocked;
    private Integer cooldownRemaining;
    private Double moonReserveQty;
    /** Jeu de paramètres actif (preset {@code TREND_MIX} : « BTC Perso Bull »…) ; null en mode FIXED. */
    private String activeSet;
    /** Régime Trend Mix du jour (UP / DOWN / RANGE) ; null en mode FIXED. */
    private String trendRegime;

    @Enumerated(EnumType.STRING)
    private RainbowLiveAction actionType;
    private Double actionAmountUsdc;
    private Double actionQuantity;
    private Double actionPrice;

    private Double cashAfter;
    private Double positionAfter;

    /**
     * Snapshot du portefeuille réel (preset live uniquement, null sinon) : statut de la lecture, wallet, instant de
     * lecture, cash USDC du pool, position réelle de l'actif, position tradable ({@code bagPercent} x réelle), cash déjà
     * réservé par les actifs servis avant, raison d'un blocage.
     */
    @Enumerated(EnumType.STRING)
    private PortfolioStatus liveStatus;
    private Long liveWalletId;
    private Instant liveFetchedAt;
    private Double liveCashUsdc;
    private Double livePositionQty;
    private Double liveTradableQty;
    private Double liveCashReserved;
    @Enumerated(EnumType.STRING)
    private LiveBlockReason liveBlockReason;

    /** Action live recommandée (plafonnée sur le réel, jamais exécutée) ; {@code actionType} reste l'action du wallet mock. */
    @Enumerated(EnumType.STRING)
    private RainbowLiveAction liveActionType;
    private Double liveActionAmountUsdc;
    private Double liveActionQuantity;

    /** Hash de la config avec laquelle la passe a été calculée. */
    private String configHash;
    private Instant computedAt;

    /** Vrai si la passe porte un snapshot live (preset lié à un wallet réel). */
    public boolean hasLiveSnapshot() {
        return liveStatus != null;
    }

    /** Recopie le snapshot et l'action live de {@code other} (rejeu 23:55 : l'action d'origine est conservée). */
    public void copyLiveFrom(RainbowLivePassBlock other) {
        liveStatus = other.liveStatus;
        liveWalletId = other.liveWalletId;
        liveFetchedAt = other.liveFetchedAt;
        liveCashUsdc = other.liveCashUsdc;
        livePositionQty = other.livePositionQty;
        liveTradableQty = other.liveTradableQty;
        liveCashReserved = other.liveCashReserved;
        liveBlockReason = other.liveBlockReason;
        liveActionType = other.liveActionType;
        liveActionAmountUsdc = other.liveActionAmountUsdc;
        liveActionQuantity = other.liveActionQuantity;
    }
}
