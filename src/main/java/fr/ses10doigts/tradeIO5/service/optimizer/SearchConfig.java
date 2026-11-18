package fr.ses10doigts.tradeIO5.service.optimizer;

/**
 * Réglages de la recherche (défauts : {@link OptimizerDefaults}).
 *
 * @param freezeThreshold    effet (points de score) sous lequel un paramètre est gelé
 * @param finalists          candidats évalués à pleine fidélité et dont on mesure le plateau
 * @param plateauBand        écart toléré sous le centre pour qu'un voisin compte dans la taille du plateau
 * @param robustnessTolerance écart relatif toléré au score de base lors des bousculades
 */
public record SearchConfig(long seed, int sobolSamples, int halvingFactor, int finalists, int sensitivityPoints,
                           double freezeThreshold, int refineSamples, int refineRadiusSteps, int refineTop, double plateauBand,
                           double robustnessTolerance, double robustnessFloor) {

    public SearchConfig {
        if (sobolSamples < 1 || halvingFactor < 2 || finalists < 1 || sensitivityPoints < 2 || refineSamples < 0
                || refineRadiusSteps < 1 || refineTop < 0 || robustnessTolerance < 0 || robustnessFloor <= 0) {
            throw new IllegalArgumentException("Réglages de recherche invalides");
        }
    }

    public static SearchConfig defaults() {
        return new SearchConfig(OptimizerDefaults.SEED, OptimizerDefaults.SOBOL_SAMPLES, OptimizerDefaults.HALVING_FACTOR,
                OptimizerDefaults.FINALISTS, OptimizerDefaults.SENSITIVITY_POINTS, OptimizerDefaults.FREEZE_THRESHOLD,
                OptimizerDefaults.REFINE_SAMPLES, OptimizerDefaults.REFINE_RADIUS_STEPS, OptimizerDefaults.REFINE_TOP,
                OptimizerDefaults.PLATEAU_BAND, OptimizerDefaults.ROBUSTNESS_TOLERANCE, OptimizerDefaults.ROBUSTNESS_FLOOR);
    }

    public SearchConfig withSeed(long newSeed) {
        return new SearchConfig(newSeed, sobolSamples, halvingFactor, finalists, sensitivityPoints, freezeThreshold, refineSamples,
                refineRadiusSteps, refineTop, plateauBand, robustnessTolerance, robustnessFloor);
    }

    public SearchConfig withSobolSamples(int samples) {
        return new SearchConfig(seed, samples, halvingFactor, finalists, sensitivityPoints, freezeThreshold, refineSamples,
                refineRadiusSteps, refineTop, plateauBand, robustnessTolerance, robustnessFloor);
    }
}
