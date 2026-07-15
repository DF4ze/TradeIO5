package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.AdxIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.EmaIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.ObvIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.OrderBookIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.RsiIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.LiquidationsIndicator;

import java.util.Map;

public class IndicatorParametersFactory {

    public static IndicatorParameters buildRsiParams(TimeFrame timeFrame, double period ){
        return new IndicatorParameters(
                IndicatorType.RSI,
                Map.of(
                        RsiIndicator.P_PERIOD_NAME, period
                ),                                                                  // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                            // Boolean
                null
        );
    }

    public static IndicatorParameters buildEmaParams(TimeFrame timeFrame, double period){
        return new IndicatorParameters(
                IndicatorType.EMA,
                Map.of(
                        EmaIndicator.P_PERIOD_NAME, period
                ),                                                                              // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                null
        );
    }

    public static IndicatorParameters buildAdxParams(TimeFrame timeFrame, double period){
        return new IndicatorParameters(
                IndicatorType.ADX,
                Map.of(
                        AdxIndicator.P_PERIOD_NAME, period
                ),                                                                              // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                null
        );
    }

    /**
     * OBV, seul indicateur interne (pas de credential) requis par
     * {@link fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy}
     * (voir {@link StrategyParametersFactory#buildMovementQualificationStrategyParam}).
     */
    public static IndicatorParameters buildObvParams(TimeFrame timeFrame, double period){
        return new IndicatorParameters(
                IndicatorType.OBV,
                Map.of(
                        ObvIndicator.P_PERIOD_NAME, period
                ),                                                                              // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                null
        );
    }

    /**
     * OPEN_INTEREST (Coinalyze) : indicateur externe, nécessite une {@code credential} déjà
     * résolue par l'appelant (ex: {@code IndicatorCredentialResolver.resolve(IndicatorType.OPEN_INTEREST)})
     * — cette factory reste volontairement statique/sans dépendance Spring, comme le reste de la
     * classe, donc ne résout pas elle-même la credential.
     */
    public static IndicatorParameters buildOpenInterestParams(TimeFrame timeFrame, ApiCredentialDTO credential){
        return new IndicatorParameters(
                IndicatorType.OPEN_INTEREST,
                Map.of(),                                                                        // Numeric (intervalHours reste sur son défaut)
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                credential
        );
    }

    /**
     * FUNDING_RATE (Coinalyze) : même principe que {@link #buildOpenInterestParams}, même
     * credential Coinalyze réutilisable pour les deux (cf. {@code IndicatorCredentialResolver}).
     */
    public static IndicatorParameters buildFundingRateParams(TimeFrame timeFrame, ApiCredentialDTO credential){
        return new IndicatorParameters(
                IndicatorType.FUNDING_RATE,
                Map.of(),                                                                        // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                credential
        );
    }

    /**
     * ORDER_BOOK (Binance, carnet public) : pas de credential requise (endpoint public), même
     * principe que {@link #buildObvParams} pour la partie "indicateur interne" — voir
     * {@link fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy}.
     */
    public static IndicatorParameters buildOrderBookParams(TimeFrame timeFrame, double priceBandPercent){
        return new IndicatorParameters(
                IndicatorType.ORDER_BOOK,
                Map.of(
                        OrderBookIndicator.P_PRICE_BAND_PERCENT, priceBandPercent
                ),                                                                              // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                null
        );
    }

    /**
     * LIQUIDATIONS (Coinalyze) : même principe que {@link #buildOpenInterestParams}, credential
     * Coinalyze résolue par l'appelant (ex: {@code IndicatorCredentialResolver.resolve(IndicatorType.LIQUIDATIONS)}) —
     * voir {@link fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy}.
     */
    public static IndicatorParameters buildLiquidationsParams(TimeFrame timeFrame, double windowHours, ApiCredentialDTO credential){
        return new IndicatorParameters(
                IndicatorType.LIQUIDATIONS,
                Map.of(
                        LiquidationsIndicator.P_WINDOW_HOURS, windowHours
                ),                                                                              // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                credential
        );
    }

}
