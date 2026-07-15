package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.LiquidationsIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.OrderBookIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import lombok.AllArgsConstructor;

public class StrategyParametersFactory {

    /**
     * Construit les 4 {@code IndicatorKey}/{@code IndicatorParameters} (EMA rapide, EMA lente, ADX,
     * RSI) requis par {@link TrendConfirmationStrategy}, ainsi que les seuils ADX/RSI de la
     * Strategy elle-même, portés par {@code StrategyParameters.numericParams} (et non par les
     * {@code IndicatorParameters} de chaque indicateur individuel).
     */
    public static StrategyParameters buildTrendConfirmationStrategyParam(TrendConfirmationParam param){

        IndicatorParameters emaFastParams = IndicatorParametersFactory.buildEmaParams(param.timeFrame, param.emaFastPeriod);
        IndicatorParameters emaSlowParams = IndicatorParametersFactory.buildEmaParams(param.timeFrame, param.emaSlowPeriod);
        IndicatorParameters adxParams = IndicatorParametersFactory.buildAdxParams(param.timeFrame, param.adxPeriod);
        IndicatorParameters rsiParams = IndicatorParametersFactory.buildRsiParams(param.timeFrame, param.rsiPeriod);

        IndicatorKey emaFastKey = new IndicatorKey(IndicatorType.EMA, param.timeFrame, emaFastParams);
        IndicatorKey emaSlowKey = new IndicatorKey(IndicatorType.EMA, param.timeFrame, emaSlowParams);
        IndicatorKey adxKey = new IndicatorKey(IndicatorType.ADX, param.timeFrame, adxParams);
        IndicatorKey rsiKey = new IndicatorKey(IndicatorType.RSI, param.timeFrame, rsiParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(emaFastKey, emaFastParams);
        params.getIndicatorParameters().put(emaSlowKey, emaSlowParams);
        params.getIndicatorParameters().put(adxKey, adxParams);
        params.getIndicatorParameters().put(rsiKey, rsiParams);

        params.getNumericParams().put(TrendConfirmationStrategy.P_ADX_LOW_THRESHOLD, param.adxLowThreshold);
        params.getNumericParams().put(TrendConfirmationStrategy.P_ADX_HIGH_THRESHOLD, param.adxHighThreshold);
        params.getNumericParams().put(TrendConfirmationStrategy.P_RSI_OVERBOUGHT_THRESHOLD, param.rsiOverboughtThreshold);
        params.getNumericParams().put(TrendConfirmationStrategy.P_RSI_OVERSOLD_THRESHOLD, param.rsiOversoldThreshold);

        return params;
    }

    @AllArgsConstructor
    public static class TrendConfirmationParam {
        TimeFrame timeFrame;
        double emaFastPeriod;
        double emaSlowPeriod;
        double adxPeriod;
        double rsiPeriod;
        double adxLowThreshold;
        double adxHighThreshold;
        double rsiOverboughtThreshold;
        double rsiOversoldThreshold;
    }

    /**
     * Construit les 3 {@code IndicatorKey}/{@code IndicatorParameters} (OPEN_INTEREST, FUNDING_RATE,
     * OBV) requis par {@link MovementQualificationStrategy}, ainsi que les 8 seuils de la Strategy
     * elle-même (voir {@code MovementQualificationStrategy.DEFAULT_*} pour les valeurs par défaut
     * utilisées si cette factory n'est pas appelée — ces constantes sont dupliquées côté appelant
     * via {@link MovementQualificationParam}, pas relues dynamiquement, pour rester cohérent avec le
     * patron {@link #buildTrendConfirmationStrategyParam} où tous les seuils sont explicites).
     * <p>
     * OPEN_INTEREST/FUNDING_RATE sont des indicateurs externes (Coinalyze) : {@code coinalyzeCredential}
     * doit être résolue par l'appelant (ex: {@code IndicatorCredentialResolver.resolve(IndicatorType.OPEN_INTEREST)},
     * même credential pour les deux types) — cette classe reste volontairement statique/sans
     * dépendance Spring, elle ne résout pas elle-même la credential.
     */
    public static StrategyParameters buildMovementQualificationStrategyParam(
            MovementQualificationParam param,
            ApiCredentialDTO coinalyzeCredential
    ){
        IndicatorParameters openInterestParams = IndicatorParametersFactory.buildOpenInterestParams(param.timeFrame, coinalyzeCredential);
        IndicatorParameters fundingRateParams = IndicatorParametersFactory.buildFundingRateParams(param.timeFrame, coinalyzeCredential);
        IndicatorParameters obvParams = IndicatorParametersFactory.buildObvParams(param.timeFrame, param.obvPeriod);

        IndicatorKey openInterestKey = new IndicatorKey(IndicatorType.OPEN_INTEREST, param.timeFrame, openInterestParams);
        IndicatorKey fundingRateKey = new IndicatorKey(IndicatorType.FUNDING_RATE, param.timeFrame, fundingRateParams);
        IndicatorKey obvKey = new IndicatorKey(IndicatorType.OBV, param.timeFrame, obvParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(openInterestKey, openInterestParams);
        params.getIndicatorParameters().put(fundingRateKey, fundingRateParams);
        params.getIndicatorParameters().put(obvKey, obvParams);

        params.getNumericParams().put(MovementQualificationStrategy.P_OI_DELTA_CASCADE_THRESHOLD, param.oiDeltaCascadeThreshold);
        params.getNumericParams().put(MovementQualificationStrategy.P_OI_DELTA_BUILDUP_THRESHOLD, param.oiDeltaBuildupThreshold);
        params.getNumericParams().put(MovementQualificationStrategy.P_FUNDING_LOW_THRESHOLD, param.fundingLowThreshold);
        params.getNumericParams().put(MovementQualificationStrategy.P_FUNDING_HIGH_THRESHOLD, param.fundingHighThreshold);
        params.getNumericParams().put(MovementQualificationStrategy.P_FUNDING_BUILDUP_SIGNAL_THRESHOLD, param.fundingBuildupSignalThreshold);
        params.getNumericParams().put(MovementQualificationStrategy.P_FUNDING_NEUTRAL_BAND, param.fundingNeutralBand);
        params.getNumericParams().put(MovementQualificationStrategy.P_PRICE_MOVE_THRESHOLD, param.priceMoveThreshold);
        params.getNumericParams().put(MovementQualificationStrategy.P_PRICE_LOOKBACK_CANDLES, param.priceLookbackCandles);

        return params;
    }

    /**
     * Valeurs par défaut alignées sur {@code MovementQualificationStrategy.DEFAULT_*} (privées dans
     * la Strategy, dupliquées ici volontairement pour ne pas changer leur visibilité juste pour ce
     * besoin) — utilisable directement via {@link #defaults(TimeFrame, double)}, ou en construisant
     * une instance avec des seuils personnalisés.
     */
    @AllArgsConstructor
    public static class MovementQualificationParam {
        TimeFrame timeFrame;
        double obvPeriod;
        double oiDeltaCascadeThreshold;
        double oiDeltaBuildupThreshold;
        double fundingLowThreshold;
        double fundingHighThreshold;
        double fundingBuildupSignalThreshold;
        double fundingNeutralBand;
        double priceMoveThreshold;
        double priceLookbackCandles;

        public static MovementQualificationParam defaults(TimeFrame timeFrame, double obvPeriod){
            return new MovementQualificationParam(
                    timeFrame, obvPeriod,
                    -0.10, 0.10,
                    0.0005, 0.01,
                    0.6, 0.3,
                    0.02, 10.0
            );
        }
    }

    /**
     * Construit les 2 {@code IndicatorKey}/{@code IndicatorParameters} (ORDER_BOOK, LIQUIDATIONS)
     * requis par {@link OrderFlowStrategy}, ainsi que les 6 seuils de la Strategy elle-même — même
     * patron que {@link #buildMovementQualificationStrategyParam}.
     * <p>
     * ORDER_BOOK est public (pas de credential) ; LIQUIDATIONS est externe (Coinalyze) :
     * {@code coinalyzeCredential} doit être résolue par l'appelant (ex:
     * {@code IndicatorCredentialResolver.resolve(IndicatorType.LIQUIDATIONS)}), cette classe reste
     * volontairement statique/sans dépendance Spring.
     */
    public static StrategyParameters buildOrderFlowStrategyParam(
            OrderFlowParam param,
            ApiCredentialDTO coinalyzeCredential
    ){
        IndicatorParameters orderBookParams = IndicatorParametersFactory.buildOrderBookParams(param.timeFrame, param.priceBandPercent);
        IndicatorParameters liquidationsParams = IndicatorParametersFactory.buildLiquidationsParams(param.timeFrame, param.liquidationsWindowHours, coinalyzeCredential);

        IndicatorKey orderBookKey = new IndicatorKey(IndicatorType.ORDER_BOOK, param.timeFrame, orderBookParams);
        IndicatorKey liquidationsKey = new IndicatorKey(IndicatorType.LIQUIDATIONS, param.timeFrame, liquidationsParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(orderBookKey, orderBookParams);
        params.getIndicatorParameters().put(liquidationsKey, liquidationsParams);

        params.getNumericParams().put(OrderFlowStrategy.P_LIQUIDATION_SKEW_THRESHOLD, param.liquidationSkewThreshold);
        params.getNumericParams().put(OrderFlowStrategy.P_LIQUIDATION_VOLUME_RATIO_THRESHOLD, param.liquidationVolumeRatioThreshold);
        params.getNumericParams().put(OrderFlowStrategy.P_ORDER_BOOK_IMBALANCE_THRESHOLD, param.orderBookImbalanceThreshold);
        params.getNumericParams().put(OrderFlowStrategy.P_PRICE_MOVE_THRESHOLD, param.priceMoveThreshold);
        params.getNumericParams().put(OrderFlowStrategy.P_PRICE_LOOKBACK_CANDLES, param.priceLookbackCandles);
        params.getNumericParams().put(OrderFlowStrategy.P_EXHAUSTION_DAMPENING_FACTOR, param.exhaustionDampeningFactor);

        return params;
    }

    /**
     * Valeurs par défaut alignées sur {@code OrderFlowStrategy.DEFAULT_*} (mêmes réserves que
     * {@link MovementQualificationParam} : point de départ, pas mesuré empiriquement).
     */
    @AllArgsConstructor
    public static class OrderFlowParam {
        TimeFrame timeFrame;
        double priceBandPercent;
        double liquidationsWindowHours;
        double liquidationSkewThreshold;
        double liquidationVolumeRatioThreshold;
        double orderBookImbalanceThreshold;
        double priceMoveThreshold;
        double priceLookbackCandles;
        double exhaustionDampeningFactor;

        public static OrderFlowParam defaults(TimeFrame timeFrame){
            return new OrderFlowParam(
                    timeFrame,
                    OrderBookIndicator.DEFAULT_PRICE_BAND_PERCENT,
                    LiquidationsIndicator.DEFAULT_WINDOW_HOURS,
                    0.3, 0.02, 0.15,
                    0.02, 10.0, 0.3
            );
        }
    }
}
