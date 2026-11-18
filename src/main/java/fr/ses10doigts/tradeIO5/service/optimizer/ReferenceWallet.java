package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;

import java.util.List;

/**
 * Wallet de référence multi-actifs à cash commun (capital normalisé) : {@code inputs} porte tous les liens chargés ; {@code select}
 * en retient un sous-ensemble et recalcule leur base de référence (cash réparti entre les seuls liens retenus).
 *
 * @param autonomyDays jours d'autonomie de la proposition d'onboarding (défaut admin)
 */
public record ReferenceWallet(ScorerInputs inputs, int autonomyDays) {

    /** Entrées restreintes aux actifs donnés (ordre du wallet), bases de référence recalculées pour ce nombre de liens. */
    public ScorerInputs select(List<String> assets) {
        List<AssetInput> kept = inputs.assets().stream().filter(a -> assets.contains(a.slot().assetSymbol())).toList();
        return inputs.withAssets(ReferenceBase.assign(kept, inputs.cash0(), autonomyDays));
    }
}
