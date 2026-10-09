package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/**
 * Mode de calcul d'un preset du bench grandeur nature. {@code FIXED} (valeur par défaut, aussi pour les lignes
 * historiques sans mode) : un seul jeu de paramètres, rejoué sur la fenêtre d'analyse à chaque passe.
 * {@code TREND_MIX} : Trend Mix ({@code TrendMixCalculator}) → jeu Bull/Bear de l'actif
 * ({@code RainbowAtrPresets.bearBull}), état de la machine persisté jour après jour.
 */
public enum RainbowLiveMode {
    FIXED,
    TREND_MIX
}
