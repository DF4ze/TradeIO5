package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/** Preset inexistant OU appartenant à un autre utilisateur (même réponse : pas de fuite d'existence). */
public class RainbowLivePresetNotFoundException extends IllegalArgumentException {

    public RainbowLivePresetNotFoundException(Long presetId) {
        super("Preset introuvable pour cet utilisateur : " + presetId);
    }
}
