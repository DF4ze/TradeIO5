package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;

/**
 * Configuration de l'étape B (optimisation par blocs). Tout ce qui décide d'une conservation ou d'un rejet est ici, jamais en dur dans
 * le code de recherche.
 *
 * @param lastEnd             fin du bloc le plus récent (J-1), les autres reculent de {@code blockMonths}
 * @param blockMonths         durée d'un bloc (24)
 * @param blockCount          nombre de blocs (4) = nombre de rotations
 * @param maxPasses           passes Bull puis Bear au maximum (3) ; arrêt dès qu'une passe ne change rien
 * @param minGainPoints       gain moyen minimal, en points de capital sur les blocs de réglage, pour retenir un réglage
 * @param plateauRatio        le plateau regroupe les valeurs dont le score est à au moins ce ratio du meilleur (0,9)
 * @param exposureGuardFactor EXPOSURE_GUARD : exposition moyenne maximale du candidat = ce facteur × celle du preset en place (1,25)
 * @param refinePoints        points de l'affinage fin autour du meilleur
 * @param widenCeiling        plafond absolu d'élargissement d'un plafond de balayage (l'Expert ne borne pas le haut de ces réglages)
 * @param maxWidenSteps       doublements successifs de la borne haute au maximum
 * @param replayMs            durée supposée d'un rejeu d'un bloc de 24 mois (estimation avant lancement)
 * @param budgetMinutes       au-delà de cette estimation (rejeux × durée), le runner s'arrête et demande
 */
public record TuneConfig(LocalDate lastEnd, int blockMonths, int blockCount, int maxPasses, double minGainPoints, double plateauRatio,
                         double exposureGuardFactor, int refinePoints, double widenCeiling, int maxWidenSteps, double replayMs,
                         double budgetMinutes) {

    public TuneConfig {
        if (blockCount < 2 || blockMonths < 1 || maxPasses < 1 || refinePoints < 2) {
            throw new IllegalArgumentException("configuration de réglage invalide");
        }
    }

    public static TuneConfig defaults(LocalDate lastEnd) {
        return new TuneConfig(lastEnd, 24, 4, 3, 0.25, 0.9, 1.25, 9, 100.0, 3, 25.0, 10.0);
    }

    public TuneConfig withMinGainPoints(double v) {
        return new TuneConfig(lastEnd, blockMonths, blockCount, maxPasses, v, plateauRatio, exposureGuardFactor, refinePoints, widenCeiling,
                maxWidenSteps, replayMs, budgetMinutes);
    }

    public TuneConfig withExposureGuardFactor(double v) {
        return new TuneConfig(lastEnd, blockMonths, blockCount, maxPasses, minGainPoints, plateauRatio, v, refinePoints, widenCeiling,
                maxWidenSteps, replayMs, budgetMinutes);
    }

    public TuneConfig withMaxPasses(int v) {
        return new TuneConfig(lastEnd, blockMonths, blockCount, v, minGainPoints, plateauRatio, exposureGuardFactor, refinePoints,
                widenCeiling, maxWidenSteps, replayMs, budgetMinutes);
    }

    public TuneConfig withMaxWidenSteps(int v) {
        return new TuneConfig(lastEnd, blockMonths, blockCount, maxPasses, minGainPoints, plateauRatio, exposureGuardFactor, refinePoints,
                widenCeiling, v, replayMs, budgetMinutes);
    }

    public TuneConfig withRefinePoints(int v) {
        return new TuneConfig(lastEnd, blockMonths, blockCount, maxPasses, minGainPoints, plateauRatio, exposureGuardFactor, v, widenCeiling,
                maxWidenSteps, replayMs, budgetMinutes);
    }
}
