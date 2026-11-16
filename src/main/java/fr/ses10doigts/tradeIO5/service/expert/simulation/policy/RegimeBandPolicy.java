package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;

/** Bande d'exposition par régime du wallet (majoritaire parmi les liens actifs) ; égalité, aucun lien actif ou RANGE ⇒ base. */
public record RegimeBandPolicy(double bullMin, double bullMax, double bearMin, double bearMax) implements ModulationPolicy {

    @Override
    public ModulationSettings settingsFor(DayState state, ModulationSettings base) {
        String regime = state.majorityRegime();
        if (DayState.BULL.equals(regime)) {
            return band(base, bullMin, bullMax);
        }
        if (DayState.BEAR.equals(regime)) {
            return band(base, bearMin, bearMax);
        }
        return base;
    }

    private static ModulationSettings band(ModulationSettings base, double min, double max) {
        return new ModulationSettings(min, max, base.power(), base.bias(), base.regulationMarginPct());
    }

    @Override
    public String label() {
        return ModulationPolicies.REGIME_BAND + "(bull " + bullMin + "-" + bullMax + ", bear " + bearMin + "-" + bearMax + ")";
    }
}
