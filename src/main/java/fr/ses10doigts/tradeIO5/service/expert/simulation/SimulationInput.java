package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.time.LocalDate;
import java.util.List;

/**
 * Tout ce que le calcul d'une simulation consomme (aucun I/O) : bougies D1 chronologiques, Trend, jeux Rainbow, plage.
 * <ul>
 *   <li>TREND : {@code up} / {@code down} / {@code signal} ignorés.</li>
 *   <li>RAINBOW : {@code signal} (UP | DOWN) désigne le régime joué ; le jeu est {@code up} (signal UP) ou {@code down}.</li>
 *   <li>AFFECTATION : {@code up} = jeu Bull, {@code down} = jeu Bear.</li>
 * </ul>
 *
 * @param from début de plage (nul : {@code to} − 24 mois) ; {@code to} fin de plage (nul : dernière bougie)
 */
public record SimulationInput(SimulationType type, String assetSymbol, List<MarketData> candles,
                              TrendMixCalculator.Params trend, RangeMapping rangeMapping, String trendLabel,
                              RainbowAtrParamSet up, RainbowAtrParamSet down, TrendRegime signal,
                              LocalDate from, LocalDate to) {

    /** Provenance des bougies : provider demandé, réellement utilisé, et substitution éventuelle (fallback). */
    public record Provenance(String requestedProvider, MarketDataSource used, String providerSymbol, boolean substituted) {
    }
}
