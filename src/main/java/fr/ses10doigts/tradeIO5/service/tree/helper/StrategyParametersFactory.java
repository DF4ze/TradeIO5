package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.LiquidationsIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.OrderBookIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.EtfFlowConfidenceStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.LinearRegressionIndicator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendAnalyzer;
import lombok.AllArgsConstructor;

public class StrategyParametersFactory {

    /**
     * Construit les 3 {@code IndicatorKey}/{@code IndicatorParameters} ({@code LINEAR_REGRESSION}
     * period court/moyen/long) requis par {@link TrendConfirmationStrategy} depuis son Étape 8
     * (2026-09-24, régression à hystérésis, cf.
     * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md}),
     * ainsi que les paramètres de combinaison — même patron que
     * {@link #buildRegressiveTrendStrategyParam}. {@code TrendConfirmationParam} n'a plus de champ
     * {@code adxPeriod}/{@code adxLowThreshold}/{@code adxHighThreshold} depuis ce lot (ADX retiré
     * de {@code TrendAnalyzer}). Les constantes de clé ({@code P_SLOPE_SCALE_FACTOR}, etc.) et leurs
     * défauts sont mutualisés depuis {@code RegressiveTrendStrategy} (seul et unique endroit où ils
     * sont définis, cf. javadoc de {@link TrendConfirmationStrategy}).
     */
    public static StrategyParameters buildTrendConfirmationStrategyParam(TrendConfirmationParam param){
        IndicatorParameters shortParams = IndicatorParametersFactory.buildLinearRegressionParams(param.timeFrame, param.shortPeriod);
        IndicatorParameters mediumParams = IndicatorParametersFactory.buildLinearRegressionParams(param.timeFrame, param.mediumPeriod);
        IndicatorParameters longParams = IndicatorParametersFactory.buildLinearRegressionParams(param.timeFrame, param.longPeriod);

        IndicatorKey shortKey = new IndicatorKey(IndicatorType.LINEAR_REGRESSION, param.timeFrame, shortParams);
        IndicatorKey mediumKey = new IndicatorKey(IndicatorType.LINEAR_REGRESSION, param.timeFrame, mediumParams);
        IndicatorKey longKey = new IndicatorKey(IndicatorType.LINEAR_REGRESSION, param.timeFrame, longParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(shortKey, shortParams);
        params.getIndicatorParameters().put(mediumKey, mediumParams);
        params.getIndicatorParameters().put(longKey, longParams);

        params.getNumericParams().put(RegressiveTrendStrategy.P_SLOPE_SCALE_FACTOR, param.slopeScaleFactor);
        params.getNumericParams().put(RegressiveTrendStrategy.P_WEIGHT_SHORT, param.weightShort);
        params.getNumericParams().put(RegressiveTrendStrategy.P_WEIGHT_MEDIUM, param.weightMedium);
        params.getNumericParams().put(RegressiveTrendStrategy.P_WEIGHT_LONG, param.weightLong);
        params.getBooleanParams().put(RegressiveTrendStrategy.P_ALIGNMENT_ENABLED, param.alignmentEnabled);

        return params;
    }

    /**
     * Valeurs par défaut figées par le walk-forward de l'Étape 8 (cf.
     * {@code RegressiveTrendStrategy.DEFAULT_*} et {@code TrendAnalyzer.SHORT_PERIOD}/
     * {@code MEDIUM_PERIOD}/{@code LONG_PERIOD}, seuls endroits où ces constantes sont définies).
     */
    @AllArgsConstructor
    public static class TrendConfirmationParam {
        TimeFrame timeFrame;
        double shortPeriod;
        double mediumPeriod;
        double longPeriod;
        double slopeScaleFactor;
        double weightShort;
        double weightMedium;
        double weightLong;
        boolean alignmentEnabled;

        public static TrendConfirmationParam defaults(TimeFrame timeFrame) {
            return new TrendConfirmationParam(
                    timeFrame,
                    TrendAnalyzer.SHORT_PERIOD, TrendAnalyzer.MEDIUM_PERIOD, TrendAnalyzer.LONG_PERIOD,
                    RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG,
                    RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED
            );
        }
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

    /**
     * Construit l'unique {@code IndicatorKey}/{@code IndicatorParameters} (ETF_FLOW) requis par
     * {@link EtfFlowConfidenceStrategy}, ainsi que les 4 seuils de la Strategy elle-même — même
     * patron que {@link #buildMovementQualificationStrategyParam}/{@link #buildOrderFlowStrategyParam}.
     * <p>
     * ETF_FLOW est externe (SoSoValue) : {@code sosoValueCredential} doit être résolue par
     * l'appelant (ex: {@code IndicatorCredentialResolver.resolve(IndicatorType.ETF_FLOW)}), cette
     * classe reste volontairement statique/sans dépendance Spring. Contrairement aux autres
     * factories, le {@code timeFrame} retenu ici est {@code D1} par défaut (cf.
     * {@link EtfFlowConfidenceParam#defaults()}), pas celui de la Strategy hôte — voir javadoc de
     * classe {@link EtfFlowConfidenceStrategy}, décision étude §9.3 : ETF_FLOW ne se met à jour
     * qu'une fois par jour, un lookback H1 serait un décalage d'échelle.
     */
    public static StrategyParameters buildEtfFlowConfidenceStrategyParam(
            EtfFlowConfidenceParam param,
            ApiCredentialDTO sosoValueCredential
    ){
        IndicatorParameters etfFlowParams = IndicatorParametersFactory.buildEtfFlowParams(param.timeFrame, sosoValueCredential);

        IndicatorKey etfFlowKey = new IndicatorKey(IndicatorType.ETF_FLOW, param.timeFrame, etfFlowParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(etfFlowKey, etfFlowParams);

        params.getNumericParams().put(EtfFlowConfidenceStrategy.P_FLOW_SIGNIFICANCE_THRESHOLD_USD, param.flowSignificanceThresholdUsd);
        params.getNumericParams().put(EtfFlowConfidenceStrategy.P_MAGNITUDE_SCALE_FACTOR, param.magnitudeScaleFactor);
        params.getNumericParams().put(EtfFlowConfidenceStrategy.P_PRICE_MOVE_THRESHOLD, param.priceMoveThreshold);
        params.getNumericParams().put(EtfFlowConfidenceStrategy.P_PRICE_LOOKBACK_CANDLES, param.priceLookbackCandles);

        return params;
    }

    /**
     * Valeurs par défaut alignées sur {@code EtfFlowConfidenceStrategy.DEFAULT_*} (mêmes réserves
     * que {@link MovementQualificationParam}/{@link OrderFlowParam} : point de départ, pas mesuré
     * empiriquement — cf. docs/etudes/etude-branchement-etf-flow-confidence-modulator.md §9.2).
     */
    @AllArgsConstructor
    public static class EtfFlowConfidenceParam {
        TimeFrame timeFrame;
        double flowSignificanceThresholdUsd;
        double magnitudeScaleFactor;
        double priceMoveThreshold;
        double priceLookbackCandles;

        public static EtfFlowConfidenceParam defaults(){
            return new EtfFlowConfidenceParam(
                    TimeFrame.D1,
                    50_000_000.0, 3.0,
                    0.02, 1.0
            );
        }
    }
    /**
     * Construit les 3 {@code IndicatorKey}/{@code IndicatorParameters} ({@code LINEAR_REGRESSION}
     * period court/moyen/long, 7/14/30 par défaut) requis par {@link RegressiveTrendStrategy},
     * ainsi que les paramètres de combinaison (facteur d'échelle de pente, poids par fenêtre)
     * portés par {@code StrategyParameters.numericParams} — même patron que
     * {@link #buildTrendConfirmationStrategyParam}.
     */
    public static StrategyParameters buildRegressiveTrendStrategyParam(RegressiveTrendParam param) {
        IndicatorParameters shortParams = IndicatorParametersFactory.buildLinearRegressionParams(param.timeFrame, param.shortPeriod);
        IndicatorParameters mediumParams = IndicatorParametersFactory.buildLinearRegressionParams(param.timeFrame, param.mediumPeriod);
        IndicatorParameters longParams = IndicatorParametersFactory.buildLinearRegressionParams(param.timeFrame, param.longPeriod);

        IndicatorKey shortKey = new IndicatorKey(IndicatorType.LINEAR_REGRESSION, param.timeFrame, shortParams);
        IndicatorKey mediumKey = new IndicatorKey(IndicatorType.LINEAR_REGRESSION, param.timeFrame, mediumParams);
        IndicatorKey longKey = new IndicatorKey(IndicatorType.LINEAR_REGRESSION, param.timeFrame, longParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(shortKey, shortParams);
        params.getIndicatorParameters().put(mediumKey, mediumParams);
        params.getIndicatorParameters().put(longKey, longParams);

        params.getNumericParams().put(RegressiveTrendStrategy.P_SLOPE_SCALE_FACTOR, param.slopeScaleFactor);
        params.getNumericParams().put(RegressiveTrendStrategy.P_WEIGHT_SHORT, param.weightShort);
        params.getNumericParams().put(RegressiveTrendStrategy.P_WEIGHT_MEDIUM, param.weightMedium);
        params.getNumericParams().put(RegressiveTrendStrategy.P_WEIGHT_LONG, param.weightLong);
        params.getBooleanParams().put(RegressiveTrendStrategy.P_ALIGNMENT_ENABLED, param.alignmentEnabled);

        return params;
    }

    /**
     * Valeurs par défaut alignées sur {@code RegressiveTrendStrategy.DEFAULT_*} — figées par le
     * walk-forward de l'Étape 8 de la roadmap Trend unifié (2026-09-24, cf.
     * {@code docs/etudes/spec-composition-trend-unifie.md} §10.1/10.3), appliquées à cette Strategy
     * même si elle n'est branchée dans aucune Opinion par défaut (pas d'impact prod).
     */
    @AllArgsConstructor
    public static class RegressiveTrendParam {
        TimeFrame timeFrame;
        double shortPeriod;
        double mediumPeriod;
        double longPeriod;
        double slopeScaleFactor;
        double weightShort;
        double weightMedium;
        double weightLong;
        boolean alignmentEnabled;

        public static RegressiveTrendParam defaults(TimeFrame timeFrame) {
            return new RegressiveTrendParam(
                    timeFrame,
                    TrendAnalyzer.SHORT_PERIOD, TrendAnalyzer.MEDIUM_PERIOD, TrendAnalyzer.LONG_PERIOD,
                    RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG,
                    RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED
            );
        }
    }
}
