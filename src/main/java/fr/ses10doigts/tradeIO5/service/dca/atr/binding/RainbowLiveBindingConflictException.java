package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Binding déjà existant pour ce couple (actif, wallet), ou preset déjà live ailleurs : on modifie le binding existant. */
public class RainbowLiveBindingConflictException extends IllegalArgumentException {

    public RainbowLiveBindingConflictException(String asset, Long walletId, Long existingId) {
        super("Un binding existe déjà pour " + asset + " sur le wallet " + walletId + " (id=" + existingId
                + ") : le modifier pour changer de preset live");
    }

    public RainbowLiveBindingConflictException(Long presetId, Long existingId) {
        super("Le preset " + presetId + " est déjà le preset live du binding " + existingId
                + " : un preset ne peut alimenter qu'un seul binding");
    }
}
