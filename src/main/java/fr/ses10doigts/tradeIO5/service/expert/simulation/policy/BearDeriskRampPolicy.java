package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;

/**
 * En Bear (régime majoritaire du wallet), l'exposition max décroît linéairement de sa valeur de base vers {@code bearTarget} :
 * {@code progression = min(1, joursEnBear / rampDays)} (jours du lien Bear le plus ancien). Hors Bear ⇒ base. La bande reste
 * {@code [min(base.min, max du jour), max du jour]} ; le moteur l'élargit si elle devient plus étroite que la largeur minimale admin.
 * Sans ordre de rééquilibrage, cette politique ne fait que couper les achats et accentuer les ventes déjà demandées par le Rainbow.
 */
public record BearDeriskRampPolicy(double bearTarget, int rampDays) implements ModulationPolicy {

    @Override
    public ModulationSettings settingsFor(DayState state, ModulationSettings base) {
        if (!DayState.BEAR.equals(state.majorityRegime()) || bearTarget >= base.exposureMax()) {
            return base;
        }
        double progress = Math.min(1.0, (double) state.maxDaysInRegime(DayState.BEAR) / rampDays);
        double max = base.exposureMax() + (bearTarget - base.exposureMax()) * progress;
        return new ModulationSettings(Math.min(base.exposureMin(), max), max, base.power(), base.bias(), base.regulationMarginPct());
    }

    @Override
    public String label() {
        return ModulationPolicies.BEAR_DERISK_RAMP + "(cible " + bearTarget + " %, " + rampDays + " j)";
    }
}
