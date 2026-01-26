package fr.ses10doigts.tradeIO5.model.enumerate.decision;

public enum SignalType {
    BULLISH,
    BEARISH,
    NEUTRAL;

    public boolean isOpposite(SignalType newSignal) {
        return switch (newSignal){
            case NEUTRAL -> false;
            case BEARISH -> this == BULLISH;
            case BULLISH -> this == BEARISH;
        };
    }
}