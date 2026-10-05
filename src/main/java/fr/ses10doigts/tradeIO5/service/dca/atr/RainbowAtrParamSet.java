package fr.ses10doigts.tradeIO5.service.dca.atr;

/** Jeu de paramètres COMPLET d'un Rainbow ATR (bornes + mécanisme + ATH + moon + multiplicateurs), nommé. */
public record RainbowAtrParamSet(String name, RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
}
