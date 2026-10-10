package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Transition d'état refusée sur un binding (ex. confirmation du 1ᵉʳ ordre sans armement) ; mappée en 409. */
public class RainbowLiveBindingStateException extends RuntimeException {

    public RainbowLiveBindingStateException(String message) {
        super(message);
    }
}
