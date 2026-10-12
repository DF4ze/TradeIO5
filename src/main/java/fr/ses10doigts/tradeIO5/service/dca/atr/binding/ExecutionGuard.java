package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;

/**
 * Point de passage obligatoire du moteur d'exécution : le dernier statut de tradabilité persisté sur le binding doit être
 * {@link BindingCheckStatus#OK} (paire directe) ou {@link BindingCheckStatus#TRADABLE_VIA_INTERMEDIARY} (route à deux
 * jambes : retry borné puis stock en transit côté exécuteur) ; {@code NOT_TRADABLE_WITHOUT_FIAT}, statut absent (jamais
 * vérifié) ou autre => refus.
 */
public final class ExecutionGuard {

    private ExecutionGuard() {
    }

    public static void requireExecutable(RainbowLiveBinding binding) {
        BindingCheckStatus status = binding.getTradability();
        if (status != BindingCheckStatus.OK && status != BindingCheckStatus.TRADABLE_VIA_INTERMEDIARY) {
            throw new ExecutionBlockedException("Exécution bloquée pour " + binding.getAssetSymbol() + " (wallet "
                    + binding.getWallet().getId() + ") : tradabilité " + status);
        }
    }
}
