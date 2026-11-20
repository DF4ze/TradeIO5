package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration de l'étape C3 (calage de l'exposition par profil sur le pire cas, avec marge mesurée). Tout seuil de décision est ici, fixé
 * avant de regarder les résultats ; les plafonds de drawdown sont lus dans {@link FrontierConfig#profileCaps()}.
 *
 * @param parity               blocs de C1 (4 × 24 mois), fin du bloc le plus récent ({@code lastEnd}), coût d'un rejeu : ceux de B' / C1
 * @param grid                 grille des multiplicateurs du montant de base : grille de C1 + niveaux ajoutés en C2 ({@code constantGrid})
 * @param windowMonths         durée d'une fenêtre glissante (24)
 * @param windowStepDays       pas entre deux fenêtres (30 jours), la dernière finissant à {@code lastEnd}
 * @param anchorFromMonthsBack première date de calage ancré = {@code lastEnd} − 72 mois (2020-11-17)
 * @param anchorToMonthsBack   dernière date de calage ancré = {@code lastEnd} − 12 mois (2025-11-17) : 12 mois de test restent disponibles
 * @param anchorStepMonths     pas entre deux dates T (3 mois)
 * @param testMonths           durée du replay de test après T (12)
 * @param margins              marges k testées ; calage avec le plafond / k (1 = plafond brut)
 * @param holdShare            part des T où le vrai plafond doit tenir pour retenir k (0,90)
 * @param bearFrom             début du bear 2021-22 (convention : plus haut de BTC, 2021-11-10)
 * @param bearTo               fin du bear 2021-22 (convention : creux de BTC, 2022-11-21)
 * @param bearMinOverlapDays   un test « contient le bear » s'il en recouvre au moins ce nombre de jours (90)
 */
public record ExposureCalibrationConfig(ParityConfig parity, List<Double> grid, int windowMonths, int windowStepDays, int anchorFromMonthsBack,
                                        int anchorToMonthsBack, int anchorStepMonths, int testMonths, List<Double> margins, double holdShare,
                                        LocalDate bearFrom, LocalDate bearTo, int bearMinOverlapDays) {

    public ExposureCalibrationConfig {
        if (grid.isEmpty() || margins.isEmpty() || windowMonths < 1 || windowStepDays < 1 || anchorStepMonths < 1 || testMonths < 1
                || anchorFromMonthsBack < anchorToMonthsBack || holdShare <= 0 || holdShare > 1 || margins.stream().anyMatch(k -> k < 1.0)) {
            throw new IllegalArgumentException("configuration de calage invalide");
        }
        grid = List.copyOf(grid);
        margins = List.copyOf(margins);
    }

    public static ExposureCalibrationConfig defaults(LocalDate lastEnd) {
        return new ExposureCalibrationConfig(ParityConfig.defaults(lastEnd), ExposurePolicyConfig.defaults(lastEnd).constantGrid(), 24, 30, 72, 12, 3,
                12, List.of(1.0, 1.1, 1.25, 1.5, 2.0), 0.90, LocalDate.parse("2021-11-10"), LocalDate.parse("2022-11-21"), 90);
    }

    public ExposureCalibrationConfig withGrid(List<Double> newGrid) {
        return new ExposureCalibrationConfig(parity, newGrid, windowMonths, windowStepDays, anchorFromMonthsBack, anchorToMonthsBack,
                anchorStepMonths, testMonths, margins, holdShare, bearFrom, bearTo, bearMinOverlapDays);
    }

    public LocalDate lastEnd() {
        return parity.lastEnd();
    }

    /** Dates T : {@code lastEnd} − 72 mois à {@code lastEnd} − 12 mois, tous les 3 mois (21 dates). */
    public List<LocalDate> anchors() {
        List<LocalDate> out = new ArrayList<>();
        for (int m = anchorFromMonthsBack; m >= anchorToMonthsBack; m -= anchorStepMonths) {
            out.add(lastEnd().minusMonths(m));
        }
        return List.copyOf(out);
    }

    /** Dernier jour du replay de test commencé en {@code anchor}. */
    public LocalDate testEnd(LocalDate anchor) {
        return anchor.plusMonths(testMonths).minusDays(1);
    }

    /** Jours de recouvrement entre {@code [from, to]} et le bear 2021-22. */
    public long bearOverlapDays(LocalDate from, LocalDate to) {
        LocalDate lo = from.isAfter(bearFrom) ? from : bearFrom;
        LocalDate hi = to.isBefore(bearTo) ? to : bearTo;
        return Math.max(0L, ChronoUnit.DAYS.between(lo, hi) + 1);
    }

    /** La plage recouvre le bear sur au moins {@code bearMinOverlapDays} jours. */
    public boolean overlapsBear(LocalDate from, LocalDate to) {
        return bearOverlapDays(from, to) >= bearMinOverlapDays;
    }

    /** La plage contient le bear en entier. */
    public boolean containsBear(LocalDate from, LocalDate to) {
        return !from.isAfter(bearFrom) && !to.isBefore(bearTo);
    }
}
