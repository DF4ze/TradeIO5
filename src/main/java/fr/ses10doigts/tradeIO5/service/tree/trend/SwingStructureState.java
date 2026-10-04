package fr.ses10doigts.tradeIO5.service.tree.trend;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sortie riche du calculateur {@link SwingStructureCalculator}  -  DTO/enum riche, pas d'encodage
 * numérique à ce niveau (cf. docs/etudes/etude-indicateur-trend-unifie.md sec. 5 point E, architecture
 * à 2 couches). L'encodage en {@code IndicatorResult} est la responsabilité de l'adaptateur
 * {@code SwingStructureIndicator}.
 *
 * @param regime            régime courant déduit des derniers pivots confirmés
 * @param lastSwingHigh     dernier pivot high confirmé, {@code null} si aucun
 * @param lastSwingLow      dernier pivot low confirmé, {@code null} si aucun
 * @param previousSwingHigh avant-dernier pivot high confirmé, {@code null} si moins de 2 pivots
 *                          high confirmés
 * @param previousSwingLow  avant-dernier pivot low confirmé, {@code null} si moins de 2 pivots low
 *                          confirmés
 * @param nearestSrLevels   niveaux S/R (prix de tous les pivots confirmés, high et low confondus),
 *                          triés par distance croissante au dernier close de la série, taille <= 4
 */
public record SwingStructureState(
        SwingStructureRegime regime,
        SwingPivot lastSwingHigh,
        SwingPivot lastSwingLow,
        SwingPivot previousSwingHigh,
        SwingPivot previousSwingLow,
        List<BigDecimal> nearestSrLevels
) {
}
