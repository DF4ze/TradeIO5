package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Binding ou wallet inexistant OU appartenant à un autre utilisateur (même réponse : pas de fuite d'existence). */
public class RainbowLiveBindingNotFoundException extends IllegalArgumentException {

    private RainbowLiveBindingNotFoundException(String message) {
        super(message);
    }

    public static RainbowLiveBindingNotFoundException binding(Long id) {
        return new RainbowLiveBindingNotFoundException("Binding introuvable pour cet utilisateur : " + id);
    }

    public static RainbowLiveBindingNotFoundException wallet(Long id) {
        return new RainbowLiveBindingNotFoundException("Wallet introuvable pour cet utilisateur : " + id);
    }
}
