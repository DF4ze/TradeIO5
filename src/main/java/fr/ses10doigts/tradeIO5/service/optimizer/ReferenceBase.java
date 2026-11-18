package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;

import java.util.List;

/**
 * Montant de base de référence d'un lien : logique de proposition d'onboarding
 * ({@link RainbowLiveOnboardingService#referenceBaseAmount} : cash alloué / (autonomyDays × multiplicateur moyen des zones)), cash du
 * wallet réparti à parts égales entre ses liens. Même valeur pour tous les candidats d'un wallet de référence : c'est la base du DCA fixe
 * et le « 1 » de {@code baseMultiplier}.
 */
public final class ReferenceBase {

    private ReferenceBase() {
    }

    public static double of(double cash0, int linkCount, int autonomyDays, double meanMultiplier) {
        return RainbowLiveOnboardingService.referenceBaseAmount(cash0 / linkCount, autonomyDays, meanMultiplier).doubleValue();
    }

    /** Liens avec leur base de référence recalculée pour ce nombre de liens (un lien sans multiplicateur moyen est laissé tel quel). */
    public static List<AssetInput> assign(List<AssetInput> assets, double cash0, int autonomyDays) {
        return assets.stream().map(a -> a.meanMultiplier() == null ? a
                : a.withReferenceBase(of(cash0, assets.size(), autonomyDays, a.meanMultiplier()))).toList();
    }
}
