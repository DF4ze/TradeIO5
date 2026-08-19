package fr.ses10doigts.tradeIO5.model.dto.tree.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;

public record IndicatorExecutionKey(
        IndicatorType indicatorType,
        IndicatorParameters parameters,
        IndicatorContext context
) {
    public static IndicatorExecutionKey of(
            Indicator indicator,
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        return new IndicatorExecutionKey(
                indicator.getType(),
                parameters,
                indicator.isGlobal() ? globalContext(context) : context
        );
    }

    /**
     * Neutralise {@code symbol} et {@code marketDataset} (qui embarque lui-même le symbole via
     * {@code MarketDataset.pair}, cf. {@code TreeAnalysisFacade.emptyDataset}) pour un indicateur
     * {@link Indicator#isGlobal() global} : deux appels différant uniquement par le symbole
     * interrogé (ex: DXY calculé pour BTC puis pour ETH) doivent partager la même entrée de cache
     * plutôt que déclencher deux appels réseau identiques — cf. javadoc {@link Indicator#isGlobal()}.
     * {@code timeframe}/{@code dependencies}/{@code clock} restent inchangés (aucune preuve que ces
     * indicateurs les ignorent, contrairement à {@code symbol}, vérifié classe par classe).
     */
    private static IndicatorContext globalContext(IndicatorContext context) {
        return new IndicatorContext(
                null,
                context.timeframe(),
                null,
                context.dependencies(),
                context.clock()
        );
    }
}