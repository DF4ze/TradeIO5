package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * Configuration de l'étape C2 (politique d'exposition par régime contre exposition constante, à plafond de drawdown égal). Tout seuil de
 * décision est ici, fixé avant de regarder les résultats ; les plafonds de drawdown sont lus dans {@link FrontierConfig#profileCaps()}.
 *
 * @param parity          blocs (4 × 24 mois), coût d'un rejeu, budget : ceux de l'étape B'
 * @param multipliers     grille du multiplicateur du montant de base de la politique (grille de C1)
 * @param constantExtra   multiplicateurs ajoutés à la grille de la constante seule (élargissement de C1, toujours appliqué : un réglage sur 3 blocs
 *                        peut ne pas encadrer un plafond que les 4 blocs encadrent) ; coût négligeable
 * @param bounds          valeurs testées pour chaque borne haute d'exposition (Bull, Bear), en % ; 100 = bande neutre = valeur de base
 * @param minWins         rotations (sur 4) où la politique doit battre la constante en gain
 * @param minStable       rotations où le même couple de bornes hautes doit revenir pour juger les réglages stables
 */
public record ExposurePolicyConfig(ParityConfig parity, List<Double> multipliers, List<Double> constantExtra, List<Double> bounds, int minWins,
                                   int minStable) {

    public ExposurePolicyConfig {
        if (multipliers.isEmpty() || bounds.isEmpty() || minWins < 1 || minStable < 1) {
            throw new IllegalArgumentException("configuration de politique d'exposition invalide");
        }
        multipliers = List.copyOf(multipliers);
        constantExtra = List.copyOf(constantExtra);
        bounds = List.copyOf(bounds);
    }

    public static ExposurePolicyConfig defaults(LocalDate lastEnd) {
        FrontierConfig f = FrontierConfig.defaults(lastEnd);
        return new ExposurePolicyConfig(f.parity(), f.multipliers(), Stream.concat(f.extendDown().stream(), f.extendUp().stream()).toList(),
                List.of(20.0, 30.0, 45.0, 65.0, 100.0), 3, 3);
    }

    /** Grille de la constante : grille de C1 + élargissements, croissante. */
    public List<Double> constantGrid() {
        return Stream.concat(multipliers.stream(), constantExtra.stream()).distinct().sorted().toList();
    }

    /** Combinaisons de la politique : bornes Bull × bornes Bear × multiplicateurs. */
    public int combos() {
        return bounds.size() * bounds.size() * multipliers.size();
    }
}
