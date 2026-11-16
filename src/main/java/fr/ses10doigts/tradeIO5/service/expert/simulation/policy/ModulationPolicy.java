package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;

/**
 * Politique de modulation du rejeu : fait varier les réglages (bande, puissance, biais, marge) jour par jour en fonction de l'état du
 * wallet et du régime. Fonction PURE (aucun I/O, aucun état) ; un seul jeu de réglages par jour pour tous les liens, comme le live.
 * La sortie est bornée par le moteur (limites admin) : une politique n'a pas à connaître ces bornes.
 */
@FunctionalInterface
public interface ModulationPolicy {

    /**
     * @param state état du wallet au jour courant (aucune donnée postérieure)
     * @param base  réglages qui s'appliqueraient sans politique ce jour-là
     * @return réglages du jour
     */
    ModulationSettings settingsFor(DayState state, ModulationSettings base);

    /** Libellé court (méta du résultat, comparaison). */
    default String label() {
        return getClass().getSimpleName();
    }
}
