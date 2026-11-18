package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Réglages de l'optimiseur de presets L1 : profil de risque (bande d'exposition et plafond de drawdown du veto), plis walk-forward
 * semestriels, recherche (une seule graine), cadences comparées et autonomie de la référence DCA fixe (360 j : protocole V6, fixée a
 * priori, jamais ajustée pour gagner).
 *
 * @param foldEnds       fins d'entraînement des plis ; le test = les {@code testMonths} mois suivants (premier jour = fin + 1)
 * @param cadenceDays    cadences de ré-optimisation comparées (jours)
 * @param cadenceStart   fin d'entraînement de la première ré-optimisation de la chaîne
 * @param autonomyDays   autonomie de la référence DCA fixe
 */
public record PresetOptimizerConfig(StartingProfile profile, List<LocalDate> foldEnds, int testMonths, SearchConfig search,
                                    List<Integer> cadenceDays, LocalDate cadenceStart, int autonomyDays) {

    public PresetOptimizerConfig {
        if (profile == null || !profile.selectable()) {
            throw new IllegalArgumentException("profil non optimisable : " + profile);
        }
        if (foldEnds.isEmpty() || testMonths < 1 || autonomyDays < 1) {
            throw new IllegalArgumentException("Au moins un pli, un test de 1 mois et une autonomie de 1 jour");
        }
        if (foldEnds.stream().distinct().count() != foldEnds.size()) {
            throw new IllegalArgumentException("Fins de plis en double");
        }
        if (cadenceDays.stream().anyMatch(d -> d < 1)) {
            throw new IllegalArgumentException("Cadence >= 1 jour");
        }
        foldEnds = foldEnds.stream().sorted().toList();
        cadenceDays = List.copyOf(cadenceDays);
    }

    /** Profil Équilibré, 6 plis semestriels (test 6 mois), graine et recherche par défaut, cadences 30 / 90 / 180 j, référence 360 j. */
    public static PresetOptimizerConfig defaults() {
        return new PresetOptimizerConfig(StartingProfile.BALANCED, OptimizerDefaults.PRESET_FOLD_ENDS.stream().map(LocalDate::parse).toList(),
                OptimizerDefaults.PRESET_TEST_MONTHS, SearchConfig.defaults(), OptimizerDefaults.PRESET_CADENCES,
                LocalDate.parse(OptimizerDefaults.PRESET_CADENCE_START), CalibrationConfig.V6_REFERENCE_AUTONOMY_DAYS);
    }

    /** Réglages d'une demande {@code PRESET_L1} (champs nuls = défauts). */
    public static PresetOptimizerConfig of(OptimizerRunRequest req) {
        PresetOptimizerConfig d = defaults();
        StartingProfile profile = req.profile() == null || req.profile().isBlank() ? d.profile() : ReplayInputsLoader.parseProfile(req.profile());
        SearchConfig search = d.search();
        if (req.seed() != null) {
            search = search.withSeed(req.seed());
        }
        if (req.sobolSamples() != null) {
            search = search.withSobolSamples(req.sobolSamples());
        }
        return new PresetOptimizerConfig(profile, req.foldEnds() == null || req.foldEnds().isEmpty() ? d.foldEnds() : req.foldEnds(),
                d.testMonths(), search, d.cadenceDays(), d.cadenceStart(), d.autonomyDays());
    }

    public PresetOptimizerConfig withFolds(List<LocalDate> ends, int months) {
        return new PresetOptimizerConfig(profile, ends, months, search, cadenceDays, cadenceStart, autonomyDays);
    }

    public PresetOptimizerConfig withSearch(SearchConfig newSearch) {
        return new PresetOptimizerConfig(profile, foldEnds, testMonths, newSearch, cadenceDays, cadenceStart, autonomyDays);
    }

    public PresetOptimizerConfig withCadences(List<Integer> days) {
        return new PresetOptimizerConfig(profile, foldEnds, testMonths, search, days, cadenceStart, autonomyDays);
    }

    public PresetOptimizerConfig withProfile(StartingProfile newProfile) {
        return new PresetOptimizerConfig(newProfile, foldEnds, testMonths, search, cadenceDays, cadenceStart, autonomyDays);
    }

    /** Dernier jour du test d'un pli. */
    public LocalDate testEnd(LocalDate foldEnd) {
        return foldEnd.plusMonths(testMonths);
    }

    public LocalDate lastTestEnd() {
        return foldEnds.stream().max(Comparator.naturalOrder()).map(this::testEnd).orElseThrow();
    }
}
