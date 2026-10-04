package fr.ses10doigts.tradeIO5.service.tree.trend;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pivot haut ou bas confirmé par {@link SwingStructureCalculator}.
 *
 * @param high           {@code true} = pivot high, {@code false} = pivot low
 * @param timestamp      horodatage de la bougie candidate au moment de sa confirmation en tant que
 *                       pivot (pas de la bougie qui déclenche la confirmation)
 * @param price          prix (high ou low selon {@code high}) du candidat confirmé
 * @param classification {@code null} pour le tout premier pivot de son type (rien à comparer)
 */
public record SwingPivot(
        boolean high,
        Instant timestamp,
        BigDecimal price,
        PivotClassification classification
) {
}
