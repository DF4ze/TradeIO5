package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

/**
 * Demande de simulation résolue : réglages effectifs (presets lus avec les droits de l'appelant, ou brouillons validés),
 * historique D1 requis et provider de référence des presets (utilisé à défaut de provider choisi).
 */
public record ResolvedSimulation(SimulationType type, String assetSymbol, TrendMixCalculator.Params trend, RangeMapping rangeMapping,
                                 String trendLabel, RainbowAtrParamSet up, RainbowAtrParamSet down, TrendRegime signal,
                                 int requiredPoints, String referenceProvider) {
}
