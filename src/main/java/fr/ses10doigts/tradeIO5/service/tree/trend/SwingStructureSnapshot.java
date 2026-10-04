package fr.ses10doigts.tradeIO5.service.tree.trend;

import java.time.Instant;
import java.util.List;

/**
 * Un point de la timeline retournée par {@link SwingStructureCalculator#computeTimeline} : l'état
 * (régime courant, pivots tout juste confirmés) associé à une bougie précise de l'historique
 * d'entrée. Introduit par le harnais de calibration
 * ({@code docs/prompts/prompt-implementation-trend-unifie-etape2.md} sec. 2.3) pour exposer
 * nativement la liste complète des pivots confirmés au fil du temps, sans rejouer
 * {@link SwingStructureCalculator#compute} sur des sous-listes croissantes.
 *
 * @param timestamp                 horodatage de la bougie associée à ce point de la timeline
 * @param regime                    régime courant à cette bougie (identique au précédent point de la
 *                                  timeline si aucun pivot n'a été confirmé sur cette bougie)
 * @param pivotsConfirmedThisCandle pivot(s) confirmé(s) exactement sur cette bougie  -  vide la
 *                                  plupart du temps, jusqu'à 2 éléments si un pivot low et un pivot
 *                                  high se confirment tous les deux sur la même bougie (cf. spec
 *                                  sec. 1.4 point 3)
 */
public record SwingStructureSnapshot(
        Instant timestamp,
        SwingStructureRegime regime,
        List<SwingPivot> pivotsConfirmedThisCandle
) {
}
