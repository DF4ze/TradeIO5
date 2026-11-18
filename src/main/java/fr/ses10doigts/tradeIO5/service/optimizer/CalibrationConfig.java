package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Réglages du calibrage walk-forward : profils, fins d'entraînement des plis (le test = les {@code testMonths} mois suivants, premier
 * jour = fin + 1) et réglages de recherche.
 */
public record CalibrationConfig(List<StartingProfile> profiles, List<LocalDate> foldEnds, int testMonths, SearchConfig search,
                                boolean trainDrawdownVeto, boolean dropFoldOnStartVeto, Map<String, Double> startOverrides) {

    /** Protocole par défaut : veto de drawdown à l'entraînement, pli écarté si le départ y est vetoé. */
    public CalibrationConfig(List<StartingProfile> profiles, List<LocalDate> foldEnds, int testMonths, SearchConfig search) {
        this(profiles, foldEnds, testMonths, search, true, true, Map.of());
    }

    /**
     * Variante expérimentale (runner manuel) : {@code trainDrawdownVeto} faux ⇒ pas de veto de drawdown à l'entraînement (il reste en test) ;
     * {@code dropFoldOnStartVeto} faux ⇒ un départ vetoé n'écarte pas le pli tant qu'un candidat retenu ne l'est pas.
     */
    public CalibrationConfig withVariant(boolean trainDrawdownVeto, boolean dropFoldOnStartVeto) {
        return new CalibrationConfig(profiles, foldEnds, testMonths, search, trainDrawdownVeto, dropFoldOnStartVeto, startOverrides);
    }

    /**
     * Protocole « V6 » (opt-in, jamais le défaut) : veto d'entraînement gardé, pli conservé si seul le départ est vetoé. À associer à une
     * référence étalée ({@link #V6_REFERENCE_AUTONOMY_DAYS} j, {@code ReferenceWallet.autonomyDays}).
     */
    public CalibrationConfig protocolV6() {
        return withVariant(true, false);
    }

    /** Autonomie de la référence du protocole V6 (jours). */
    public static final int V6_REFERENCE_AUTONOMY_DAYS = 360;

    /** Variante expérimentale : valeurs de départ (nom de paramètre → valeur) remplaçant celles de {@link L2Params#space}. */
    public CalibrationConfig withStart(Map<String, Double> overrides) {
        return new CalibrationConfig(profiles, foldEnds, testMonths, search, trainDrawdownVeto, dropFoldOnStartVeto, overrides);
    }

    /** Variante expérimentale : autre graine de recherche (robustesse du résultat à l'échantillonnage Sobol). */
    public CalibrationConfig withSeed(long seed) {
        return new CalibrationConfig(profiles, foldEnds, testMonths, search.withSeed(seed), trainDrawdownVeto, dropFoldOnStartVeto, startOverrides);
    }

    /** Variante expérimentale : autres plis (fins d'entraînement) et durée de test. */
    public CalibrationConfig withFolds(List<LocalDate> ends, int months) {
        return new CalibrationConfig(profiles, ends, months, search, trainDrawdownVeto, dropFoldOnStartVeto, startOverrides);
    }

    public CalibrationConfig {
        if (profiles.isEmpty() || foldEnds.isEmpty() || testMonths < 1) {
            throw new IllegalArgumentException("Au moins un profil, un pli et un test de 1 mois");
        }
        if (foldEnds.stream().distinct().count() != foldEnds.size()) {
            throw new IllegalArgumentException("Fins de plis en double");
        }
        startOverrides = startOverrides == null ? Map.of() : Map.copyOf(startOverrides);
        profiles = List.copyOf(profiles);
        foldEnds = foldEnds.stream().sorted().toList();
    }

    /** Les 4 profils, plis par défaut, recherche par défaut. */
    public static CalibrationConfig defaults() {
        return new CalibrationConfig(List.of(StartingProfile.PRUDENT, StartingProfile.BALANCED, StartingProfile.DYNAMIC,
                StartingProfile.FULL), OptimizerDefaults.FOLD_ENDS.stream().map(LocalDate::parse).toList(), OptimizerDefaults.TEST_MONTHS,
                SearchConfig.defaults());
    }

    /** Réglages d'une demande {@code POLICY_L2} (champs nuls = défauts). */
    public static CalibrationConfig of(OptimizerRunRequest req) {
        CalibrationConfig d = defaults();
        List<StartingProfile> profiles = req.profiles() == null || req.profiles().isEmpty() ? d.profiles()
                : req.profiles().stream().map(ReplayInputsLoader::parseProfile).toList();
        if (profiles.stream().anyMatch(p -> !p.selectable())) {
            throw new IllegalArgumentException("profil non calibrable : CUSTOM");
        }
        SearchConfig search = d.search();
        if (req.seed() != null) {
            search = search.withSeed(req.seed());
        }
        if (req.sobolSamples() != null) {
            search = search.withSobolSamples(req.sobolSamples());
        }
        return new CalibrationConfig(profiles, req.foldEnds() == null || req.foldEnds().isEmpty() ? d.foldEnds() : req.foldEnds(),
                d.testMonths(), search);
    }

    /** Dernier jour du test d'un pli. */
    public LocalDate testEnd(LocalDate foldEnd) {
        return foldEnd.plusMonths(testMonths);
    }

    public LocalDate lastTestEnd() {
        return foldEnds.stream().max(Comparator.naturalOrder()).map(this::testEnd).orElseThrow();
    }
}
