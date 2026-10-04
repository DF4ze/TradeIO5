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
 * {@link DcaResult#getPnl()}) plus le produit déjà encaissé des ventes, moins le total investi.
 * <p>
 * {@code realizedGain}/{@code potentialGain} (2026-09-13, cf. étude §15, demande explicite de
 * Clem — contrairement au reste de ce DTO, calculée via un suivi de coût de revient) : le DCA
 * Rainbow, à la différence du DCA fixe (qui ne vend jamais rien), sécurise une partie des fonds en
 * vendant en zone haute. {@code pnl}/{@code pnlPercent} ci-dessus mélangent gain réalisé (déjà en
 * cash, via les ventes) et gain potentiel (encore en position, pas garanti tant que non vendu) —
 * {@code realizedGain} isole le premier, {@code potentialGain} le second, via un suivi du coût de
 * revient de la position par la méthode du coût moyen pondéré (WAC : chaque vente consomme une
 * part du coût d'acquisition proportionnelle à la part de la position vendue, sans changer le coût
 * moyen unitaire du reste). Par construction, {@code realizedGain + potentialGain == pnl} (le coût
 * de revient total suivi égale toujours {@code totalInvested}, qu'il soit déjà "consommé" par une
 * vente ou encore attaché à la position restante).
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

    /**
     * Plus-value déjà réalisée (encaissée via les ventes), en valeur : {@code Σ (produit de vente -
     * coût de revient WAC de la quantité vendue)} sur toutes les ventes du backtest. {@code null}
     * n'arrive jamais (0 si aucune vente) ; cf. javadoc de la classe pour la méthode de calcul.
     */
    private final BigDecimal realizedGain;
    /** {@code realizedGain / totalInvested * 100}. {@code null} si {@code totalInvested <= 0}. */
    private final BigDecimal realizedGainPercent;
    /**
     * Plus-value potentielle (encore en position, pas garantie tant que non vendue) :
     * {@code currentValue - coût de revient WAC de la position restante à endDate}.
     */
    private final BigDecimal potentialGain;
    /** {@code potentialGain / totalInvested * 100}. {@code null} si {@code totalInvested <= 0}. */
    private final BigDecimal potentialGainPercent;

    private final List<RainbowDcaOccurrence> occurrences;

    /** DCA à montant fixe (même baseAmount, même période, fréquence D1) pour comparaison directe. */
    private final DcaResult fixedDcaComparison;
}
