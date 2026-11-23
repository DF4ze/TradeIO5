package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;

/**
 * Configuration du diagnostic des switchs Bull → Bear (étape E0). Tout seuil de décision est ici, fixé avant de regarder les résultats.
 *
 * @param end                 dernier jour clos des bougies
 * @param seed                graine des tirages du hasard (rapportée)
 * @param nullDraws           nombre de dates tirées uniformément dans l'historique rejouable (1 000)
 * @param shortHorizon        horizon court de la baisse restante / des rendements, en jours (90)
 * @param longHorizon         horizon long, en jours (180)
 * @param returnHorizons      horizons des rendements après le switch, en jours (30, 90, 180)
 * @param falseSwitchDays     fenêtre du faux switch : nouveau plus haut de clôture (depuis le dernier retour Bear → Bull, comme la mesure 1) avant S + N jours (60)
 * @param feePct              frais par ordre, en % (modèle du simulateur : {@link OptimizerDefaults#SIMPLIFIED_FEE_PCT})
 * @param minSwitches         en dessous, verdict {@code NON_CONCLUANT} (5)
 * @param minNetPositiveShare part minimale de switchs fermés à gain de coupure net > 0 pour la condition (a) (0,60)
 * @param phaseLookbackDays   phase de marché d'un switch : rendement de P_S sur ce nombre de jours (≥ 0 = haussière), causal (365)
 */
public record SwitchDiagnosticConfig(LocalDate end, long seed, int nullDraws, int shortHorizon, int longHorizon, int[] returnHorizons,
                                     int falseSwitchDays, double feePct, int minSwitches, double minNetPositiveShare, int phaseLookbackDays) {

    public SwitchDiagnosticConfig {
        if (nullDraws < 1 || shortHorizon < 1 || longHorizon < shortHorizon || falseSwitchDays < 1 || minSwitches < 1) {
            throw new IllegalArgumentException("configuration du diagnostic de switchs invalide");
        }
        returnHorizons = returnHorizons.clone();
    }

    public static SwitchDiagnosticConfig defaults(LocalDate end) {
        return new SwitchDiagnosticConfig(end, OptimizerDefaults.SEED, 1000, 90, 180, new int[]{30, 90, 180}, 60,
                OptimizerDefaults.SIMPLIFIED_FEE_PCT, 5, 0.60, 365);
    }
}
