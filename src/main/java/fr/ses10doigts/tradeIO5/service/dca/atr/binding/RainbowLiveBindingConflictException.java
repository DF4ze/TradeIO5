package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** L'utilisateur a déjà un preset live pour cet actif : on bascule par modification du binding existant. */
public class RainbowLiveBindingConflictException extends IllegalArgumentException {

    public RainbowLiveBindingConflictException(String asset, Long existingId) {
        super("Un binding existe déjà pour " + asset + " (id=" + existingId + ") : le modifier pour changer de preset live");
    }
}
