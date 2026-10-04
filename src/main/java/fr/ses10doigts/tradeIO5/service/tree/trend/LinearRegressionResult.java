package fr.ses10doigts.tradeIO5.service.tree.trend;

/**
 * Résultat d'une régression linéaire (OLS) sur une fenêtre de bougies — voir
 * {@link LinearRegressionCalculator}.
 *
 * @param slope           pente brute (unité de prix par bougie)
 * @param normalizedSlope pente / prix moyen de la fenêtre (fraction de prix par bougie, signée) —
 *                        comparable entre actifs/volatilités, contrairement à {@code slope}
 * @param r2              coefficient de détermination (qualité de l'ajustement, 0=bruit, 1=parfait)
 * @param regressionValue valeur de la droite ajustée à la dernière bougie de la fenêtre
 */
public record LinearRegressionResult(
        double slope,
        double normalizedSlope,
        double r2,
        double regressionValue
) {
}
