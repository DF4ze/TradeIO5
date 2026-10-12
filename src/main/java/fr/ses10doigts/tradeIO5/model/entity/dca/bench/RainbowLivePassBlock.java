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
 * Signal du jour d'un preset (passe 23:55 UTC) : indicateurs, états des machines, action voulue par le moteur (avant tout
 * plafond de portefeuille). Champs nullables (Hibernate lit le bloc entier comme {@code null} tant que la passe n'a pas été
 * jouée).
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

    /** Hash de la config avec laquelle la passe a été calculée. */
    private String configHash;
    private Instant computedAt;
}
