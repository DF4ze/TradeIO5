package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;

/**
 * Point de passage obligatoire du moteur d'exécution : le dernier statut de tradabilité persisté sur le binding doit être
 * {@link BindingCheckStatus#OK}, ou {@link BindingCheckStatus#TRADABLE_VIA_BRIDGE} <b>avec un chemin calculé</b> (plan avec
 * étapes pour cet actif). {@code NOT_TRADABLE_WITHOUT_FIAT}, statut absent (jamais vérifié) ou autre => refus.
 */
public final class ExecutionGuard {

    private ExecutionGuard() {
    }

    /** Sans chemin calculé : seul {@code OK} passe. */
    public static void requireExecutable(RainbowLiveBinding binding) {
        requireExecutable(binding, false);
    }

    /** @param pathComputed un chemin d'achat chiffré (étapes du plan) existe pour l'actif : autorise {@code TRADABLE_VIA_BRIDGE} */
    public static void requireExecutable(RainbowLiveBinding binding, boolean pathComputed) {
        BindingCheckStatus status = binding.getTradability();
        boolean executable = status == BindingCheckStatus.OK || (status == BindingCheckStatus.TRADABLE_VIA_BRIDGE && pathComputed);
        if (!executable) {
            throw new ExecutionBlockedException("Exécution bloquée pour " + binding.getAssetSymbol() + " (wallet "
                    + binding.getWallet().getId() + ") : tradabilité " + status);
        }
    }
}
