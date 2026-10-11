package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Lien déjà existant pour ce couple (actif, wallet) : on modifie le lien existant. */
public class RainbowLiveBindingConflictException extends IllegalArgumentException {

    public RainbowLiveBindingConflictException(String asset, Long walletId, Long existingId) {
        super("Un binding existe déjà pour " + asset + " sur le wallet " + walletId + " (id=" + existingId
                + ") : le modifier pour changer de preset live");
    }
}
