package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration de l'étape C1 (frontière exposition → gain / drawdown). Tout seuil de décision est ici, fixé avant de regarder les résultats.
 *
 * @param parity               blocs, tolérance de calibrage, bande de bruit, seuils de verdict (ceux de l'étape B', inchangés)
 * @param multipliers          grille du multiplicateur du montant de base du lien (seul levier)
 * @param extendUp             multiplicateurs ajoutés une fois si un plafond de profil n'est pas atteint par la grille et que l'exposition ne sature pas
 * @param extendDown           multiplicateurs ajoutés une fois si le plus bas niveau dépasse déjà un plafond
 * @param saturationElasticity un niveau est « saturé » si l'exposition n'a gagné que moins de cette fraction de la croissance proportionnelle
 *                             au multiplicateur par rapport au niveau précédent (moyenne des blocs) ; 0,25
 */
public record FrontierConfig(ParityConfig parity, List<Double> multipliers, List<Double> extendUp, List<Double> extendDown,
                             double saturationElasticity) {

    public FrontierConfig {
        if (multipliers.isEmpty() || saturationElasticity <= 0) {
            throw new IllegalArgumentException("configuration de frontière invalide");
        }
    }

    public static FrontierConfig defaults(LocalDate lastEnd) {
        return new FrontierConfig(ParityConfig.defaults(lastEnd), List.of(0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0),
                List.of(24.0, 32.0, 48.0, 64.0), List.of(0.25, 0.125), 0.25);
    }

    /** Plafonds de drawdown (en %, négatifs) lus dans {@link ModulationDefaults} : jamais codés en dur ici. */
    public static Map<StartingProfile, Double> profileCaps() {
        Map<StartingProfile, Double> caps = new LinkedHashMap<>();
        caps.put(StartingProfile.PRUDENT, cap(ModulationDefaults.DRAWDOWN_CAP_PRUDENT_PCT));
        caps.put(StartingProfile.BALANCED, cap(ModulationDefaults.DRAWDOWN_CAP_BALANCED_PCT));
        caps.put(StartingProfile.DYNAMIC, cap(ModulationDefaults.DRAWDOWN_CAP_DYNAMIC_PCT));
        caps.put(StartingProfile.FULL, cap(ModulationDefaults.DRAWDOWN_CAP_FULL_PCT));
        return caps;
    }

    private static double cap(String name) {
        return ModulationDefaults.limit(name).defaultValue().doubleValue();
    }
}
