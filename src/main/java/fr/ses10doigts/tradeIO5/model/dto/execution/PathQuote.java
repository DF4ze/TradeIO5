package fr.ses10doigts.tradeIO5.model.dto.execution;

import java.math.BigDecimal;
import java.util.List;

/**
 * Devis d'un chemin d'achat (lecture seule, aucun ordre). {@code totalCostPct} = somme brute des coûts des jambes
 * (frais + demi-spread + slippage) en % du montant ; nul si {@code NO_PATH}. {@code underfunded} : aucune source ne
 * couvre le montant, devis sur la meilleure source détenue. {@code routeKey} : route choisie ; {@code routes} : toutes les
 * routes chiffrées (choisies ou seulement affichées), de la moins chère à la plus chère.
 */
public record PathQuote(PathStatus status, String source, String target, BigDecimal amount, List<PathLeg> legs,
                        BigDecimal totalCostPct, FeeTestLevel feeTestLevel, PathEstimation estimation,
                        boolean underfunded, String routeKey, List<RouteQuote> routes) {

    public PathQuote(PathStatus status, String source, String target, BigDecimal amount, List<PathLeg> legs,
                     BigDecimal totalCostPct, FeeTestLevel feeTestLevel, PathEstimation estimation, boolean underfunded) {
        this(status, source, target, amount, legs, totalCostPct, feeTestLevel, estimation, underfunded, null, List.of());
    }

    public static PathQuote noPath(String target, BigDecimal amount) {
        return new PathQuote(PathStatus.NO_PATH, null, target, amount, List.of(), null, null, null, false);
    }

    public static PathQuote noPath(String target, BigDecimal amount, List<RouteQuote> routes) {
        return new PathQuote(PathStatus.NO_PATH, null, target, amount, List.of(), null, null, null, false, null, routes);
    }
}
