package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;

/** Référence : renvoie les réglages de base tels quels. */
public record ConstantPolicy() implements ModulationPolicy {

    @Override
    public ModulationSettings settingsFor(DayState state, ModulationSettings base) {
        return base;
    }

    @Override
    public String label() {
        return ModulationPolicies.CONSTANT;
    }
}
