package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration de l'étape D0 (réglage récent persistant ?). Tout seuil de décision est ici, jamais en dur dans le code de recherche ni
 * dans la règle de verdict (fixée avant de regarder, jamais modifiée après lecture).
 *
 * @param firstT              premier T (fin de la vue de réglage)
 * @param lastCandle          dernière bougie disponible : un T n'est retenu que si ses plus longs 6 mois suivants sont complets
 * @param stepMonths          pas entre deux T (2)
 * @param tuneMonths          durée de la vue de réglage (24)
 * @param horizons            horizons de test en mois (3 et 6) ; le dernier est l'horizon de verdict
 * @param rounds              tours Bull puis Bear au maximum (2)
 * @param trialsPerSearch     essais visés par recherche (~1 000 par jeu et par tour, SearchEngine)
 * @param exposureGuardFactor garde-fou : exposition moyenne sur la vue de réglage ≤ ce facteur × celle du preset en place (1,25)
 * @param perturbations       perturbations aléatoires du preset en place par T (20)
 * @param maxPerturbationDraws tirages maximum pour obtenir ces perturbations acceptées par le garde-fou
 * @param seed                graine (recherches et perturbations)
 * @param replayMs            durée supposée d'un rejeu de 24 mois (estimation ; la durée mesurée la remplace quand elle existe)
 * @param budgetMinutes       au-delà de cette estimation, le runner s'arrête et demande
 * @param minShare            part minimale de T gagnants (60 %)
 * @param calibrationIterations itérations maximales du calage du DCA à exposition égale
 * @param calibrationTolerancePts tolérance du calage (points d'exposition)
 */
public record RecentTuneConfig(LocalDate firstT, LocalDate lastCandle, int stepMonths, int tuneMonths, List<Integer> horizons, int rounds,
                               int trialsPerSearch, double exposureGuardFactor, int perturbations, int maxPerturbationDraws, long seed,
                               double replayMs, double budgetMinutes, double minShare, int calibrationIterations,
                               double calibrationTolerancePts) {

    public RecentTuneConfig {
        if (stepMonths < 1 || tuneMonths < 1 || horizons.isEmpty() || rounds < 1 || trialsPerSearch < 100 || perturbations < 1
                || maxPerturbationDraws < perturbations || minShare <= 0 || minShare > 1) {
            throw new IllegalArgumentException("configuration de réglage récent invalide");
        }
        horizons = List.copyOf(horizons);
    }

    public static RecentTuneConfig defaults(LocalDate lastCandle) {
        return new RecentTuneConfig(LocalDate.parse("2020-11-17"), lastCandle, 2, 24, List.of(3, 6), 2, 1000, 1.25, 20, 100,
                OptimizerDefaults.SEED, 25.0, 10.0, 0.6, 40, 0.5);
    }

    public RecentTuneConfig withTrials(int trials) {
        return new RecentTuneConfig(firstT, lastCandle, stepMonths, tuneMonths, horizons, rounds, trials, exposureGuardFactor,
                perturbations, maxPerturbationDraws, seed, replayMs, budgetMinutes, minShare, calibrationIterations, calibrationTolerancePts);
    }

    public RecentTuneConfig withFirstT(LocalDate t) {
        return new RecentTuneConfig(t, lastCandle, stepMonths, tuneMonths, horizons, rounds, trialsPerSearch, exposureGuardFactor,
                perturbations, maxPerturbationDraws, seed, replayMs, budgetMinutes, minShare, calibrationIterations, calibrationTolerancePts);
    }

    public RecentTuneConfig withRounds(int r) {
        return new RecentTuneConfig(firstT, lastCandle, stepMonths, tuneMonths, horizons, r, trialsPerSearch, exposureGuardFactor,
                perturbations, maxPerturbationDraws, seed, replayMs, budgetMinutes, minShare, calibrationIterations, calibrationTolerancePts);
    }

    public RecentTuneConfig withPerturbations(int n, int maxDraws) {
        return new RecentTuneConfig(firstT, lastCandle, stepMonths, tuneMonths, horizons, rounds, trialsPerSearch, exposureGuardFactor, n,
                maxDraws, seed, replayMs, budgetMinutes, minShare, calibrationIterations, calibrationTolerancePts);
    }

    public RecentTuneConfig withGuardFactor(double factor) {
        return new RecentTuneConfig(firstT, lastCandle, stepMonths, tuneMonths, horizons, rounds, trialsPerSearch, factor, perturbations,
                maxPerturbationDraws, seed, replayMs, budgetMinutes, minShare, calibrationIterations, calibrationTolerancePts);
    }

    /** Horizon le plus long (mois) : un T n'existe que si {@code T + maxHorizon ≤ lastCandle}. */
    public int maxHorizon() {
        return horizons.stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    /** Horizon du verdict (le plus long). */
    public int verdictHorizon() {
        return maxHorizon();
    }

    /** Dates T : {@code firstT + k × stepMonths} tant que les plus longs mois suivants sont complets. */
    public List<LocalDate> dates() {
        List<LocalDate> out = new ArrayList<>();
        for (int k = 0;; k++) {
            LocalDate t = firstT.plusMonths((long) k * stepMonths);
            if (t.plusMonths(maxHorizon()).isAfter(lastCandle)) {
                break;
            }
            out.add(t);
        }
        return List.copyOf(out);
    }

    /** Graine d'une recherche (T, jeu, tour) : déterministe, indépendante de l'ordre d'exécution. */
    public long searchSeed(LocalDate t, int setIndex, int round) {
        return seed + t.toEpochDay() * 31L + setIndex * 7L + round * 3L;
    }

    /** Graine des perturbations d'un T. */
    public long perturbationSeed(LocalDate t) {
        return seed * 17L + t.toEpochDay();
    }
}
