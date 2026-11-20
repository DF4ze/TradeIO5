package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;

/**
 * Configuration de l'étape B' (mesure à exposition égale). Tout seuil de décision est ici, fixé avant de regarder les résultats.
 *
 * @param lastEnd        fin du bloc le plus récent (J-1) ; même blocs que l'étape B
 * @param blockMonths    durée d'un bloc (24)
 * @param blockCount     nombre de blocs (4)
 * @param tolerancePts   écart d'exposition maximal, en points, entre le DCA calé et le preset (0,5)
 * @param maxIterations  rejeux de dichotomie au maximum par calibrage
 * @param shiftCount     nombre de décalages de date de début pour la bande de bruit (20)
 * @param shiftMaxDays   plus grand décalage, en jours (60) ; les décalages sont réguliers : {@code round(i × max / (count − 1))}
 * @param minBlocksPass  blocs à satisfaire pour conclure « timing utile » (3 sur 4)
 * @param drawdownMargin marge de drawdown tolérée du preset sur le DCA égal, en points (5)
 * @param bandPercentile percentile de la bande de bruit à dépasser (0,8)
 * @param replayMs       durée supposée d'un rejeu (estimation avant lancement)
 * @param budgetMinutes  au-delà, le runner s'arrête et demande
 */
public record ParityConfig(LocalDate lastEnd, int blockMonths, int blockCount, double tolerancePts, int maxIterations, int shiftCount,
                           int shiftMaxDays, int minBlocksPass, double drawdownMargin, double bandPercentile, double replayMs,
                           double budgetMinutes) {

    public ParityConfig {
        if (blockCount < 1 || blockMonths < 1 || shiftCount < 2 || maxIterations < 1 || tolerancePts <= 0) {
            throw new IllegalArgumentException("configuration de parité invalide");
        }
    }

    public static ParityConfig defaults(LocalDate lastEnd) {
        return new ParityConfig(lastEnd, 24, 4, 0.5, 40, 20, 60, 3, 5.0, 0.8, 25.0, 5.0);
    }
}
