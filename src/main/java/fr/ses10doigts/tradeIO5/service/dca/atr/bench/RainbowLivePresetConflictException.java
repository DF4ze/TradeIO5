package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/** Nom de preset déjà utilisé par l'utilisateur pour cet actif. */
public class RainbowLivePresetConflictException extends IllegalArgumentException {

    public RainbowLivePresetConflictException(String name, String asset) {
        super("Un preset '" + name + "' existe déjà pour " + asset);
    }
}
