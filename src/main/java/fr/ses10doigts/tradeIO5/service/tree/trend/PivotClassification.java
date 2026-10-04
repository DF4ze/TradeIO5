package fr.ses10doigts.tradeIO5.service.tree.trend;

/**
 * Classification d'un {@link SwingPivot} confirmé, par comparaison au pivot confirmé précédent du
 * même type (cf. algorithme détaillé de {@link SwingStructureCalculator}) :
 * <ul>
 *     <li>{@code HH} (Higher High) / {@code LH} (Lower High)  -  pour un pivot high ;</li>
 *     <li>{@code HL} (Higher Low) / {@code LL} (Lower Low)  -  pour un pivot low.</li>
 * </ul>
 */
public enum PivotClassification {
    HH, HL, LH, LL
}
