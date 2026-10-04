package fr.ses10doigts.tradeIO5.service.tree.trend;

import java.time.Instant;

/**
 * Un point de la timeline produite par {@link LinearRegressionCalculator#computeTimeline} — même
 * rôle que {@link SwingStructureSnapshot} pour SWING_STRUCTURE : une valeur par bougie, utile au
 * benchmark de calibration et à la page de visualisation (tracer la courbe de régression dans le
 * temps, pas seulement sa dernière valeur).
 */
public record LinearRegressionSnapshot(
        Instant timestamp,
        double regressionValue,
        double normalizedSlope,
        double r2
) {
}
