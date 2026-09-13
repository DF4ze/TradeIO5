package fr.ses10doigts.tradeIO5.service.dca;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Occurrence journalière (résolution D1) d'un backtest Rainbow : prix/SMA du jour, zone
 * classifiée ({@link RainbowZone}), état des deux machines à état (achat/vente) après traitement
 * de ce jour, et l'action effectivement exécutée le cas échéant. Cf. {@link RainbowDcaBacktestService}
 * pour la règle exacte.
 */
@Builder
@Data
public class RainbowDcaOccurrence {

    private final LocalDate date;
    private final BigDecimal close;
    private final BigDecimal sma;
    private final RainbowZone zone;

    /** État de la machine à état achat (armée sous percdown2) après traitement de ce jour. */
    private final ArmState buyState;
    /** État de la machine à état vente (armée au-dessus de percup3) après traitement de ce jour. */
    private final ArmState sellState;

    private final BuyAction buyAction;
    private final SellAction sellAction;

    /** Multiplicateur appliqué à baseAmount pour un achat (2/1/0.5 zone, 3 déclenché) ; null si aucun achat. */
    private final BigDecimal buyMultiplier;

    /** Montant effectivement investi ce jour (zéro si aucun achat, y compris si bloqué par le cooldown). */
    private final BigDecimal amountInvested;
    private final BigDecimal quantityBought;

    private final BigDecimal quantitySold;
    private final BigDecimal saleProceeds;

    /** Quantité totale détenue après traitement de ce jour. */
    private final BigDecimal positionAfter;

    /** Jours de cooldown restants après traitement de ce jour (0 = achat de nouveau possible dès demain). */
    private final int cooldownRemaining;

    public enum ArmState { NONE, ARMED }

    public enum BuyAction { NONE, ZONE, TRIGGERED }

    public enum SellAction { NONE, TRIGGERED }
}
