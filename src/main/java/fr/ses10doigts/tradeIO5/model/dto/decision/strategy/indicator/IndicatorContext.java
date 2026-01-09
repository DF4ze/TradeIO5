package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.dto.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
public class IndicatorContext {

    /** Actif / marché concerné (BTC/USDT, ETH/EUR, etc.) */
    private final String symbol;

    /** Timeframe du calcul (1m, 5m, 1h, 1d…) */
    private final TimeFrame timeframe;

    /** Séries de données marché (au minimum clôtures) */
    private final MarketDataSeries marketData;

    /**
     * Résultats d’indicateurs déjà calculés
     * (utile pour MACD, bandes, etc.)
     */
    private final Map<IndicatorDependencyKey, IndicatorSnapshot> dependencies;

    /** Moment logique du calcul (audit / replay) */
    private final Instant evaluationTime;
}