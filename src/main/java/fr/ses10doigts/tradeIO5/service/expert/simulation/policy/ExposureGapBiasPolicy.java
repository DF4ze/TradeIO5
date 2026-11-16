package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;

/**
 * Biais proportionnel à l'écart d'exposition : {@code clamp(gain × (cible − exposition) / 100, ±biasMax)}, cible = milieu de la bande
 * de base. Exposition inconnue ⇒ base. {@code biasMax} = borne absolue du biais ; les limites admin sont appliquées ensuite par le moteur.
 */
public record ExposureGapBiasPolicy(double gain, double biasMax) implements ModulationPolicy {

    @Override
    public ModulationSettings settingsFor(DayState state, ModulationSettings base) {
        if (state.exposurePct() == null) {
            return base;
        }
        double target = (base.exposureMin() + base.exposureMax()) / 2.0;
        double bias = Math.clamp(gain * (target - state.exposurePct()) / 100.0, -biasMax, biasMax);
        return new ModulationSettings(base.exposureMin(), base.exposureMax(), base.power(), bias, base.regulationMarginPct());
    }

    @Override
    public String label() {
        return ModulationPolicies.EXPOSURE_GAP_BIAS + "(gain " + gain + ")";
    }
}
