package fr.ses10doigts.tradeIO5.service.dca;

import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Résultat complet d'un backtest DCA Rainbow, avec le bloc comparatif DCA à montant fixe (même
 * symbole/période/baseAmount, fréquence D1) produit par {@link DcaCalculatorService#calculate}.
 * <p>
 * {@code pnl} valorise la position restante au prix courant réel (même mécanique que
 * {@link DcaResult#getPnl()}) plus le produit déjà encaissé des ventes, moins le total investi —
 * pas besoin d'un suivi de prix de revient (coût moyen pondéré) pour ce calcul global, seul
 * {@code avgBuyPrice} l'utilise à titre informatif.
 */
@Builder
@Data
public class RainbowDcaBacktestResult {

    private final String symbol;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final RainbowDcaBacktestRequest parameters;

    private final int occurrenceCount;
    private final int zoneBuyCount;
    private final int buyTriggeredCount;
    private final int sellTriggeredCount;

    private final BigDecimal totalInvested;
    private final BigDecimal totalQuantityBought;
    private final BigDecimal totalQuantitySold;
    private final BigDecimal totalSaleProceeds;
    private final BigDecimal remainingQuantity;

    /** Prix moyen d'achat pondéré ({@code totalInvested / totalQuantityBought}), informatif. */
    private final BigDecimal avgBuyPrice;

    private final BigDecimal currentPrice;
    private final BigDecimal currentValue;
    private final BigDecimal pnl;
    private final BigDecimal pnlPercent;

    private final List<RainbowDcaOccurrence> occurrences;

    /** DCA à montant fixe (même baseAmount, même période, fréquence D1) pour comparaison directe. */
    private final DcaResult fixedDcaComparison;
}
