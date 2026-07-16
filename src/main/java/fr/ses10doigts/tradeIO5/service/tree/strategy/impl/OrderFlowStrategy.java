package fr.ses10doigts.tradeIO5.service.tree.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.LiquidationsIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.OrderBookIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flux d'ordres (étude "nouvelles-opinions-indicateurs-non-branches" §4) : combine ORDER_BOOK
 * (positionnement actuel, snapshot Binance) et LIQUIDATIONS (flux forcé récent, Coinalyze) pour
 * distinguer un flush confirmé (continuation) d'un flush en train de s'épuiser (carnet qui se
 * reconstruit dans le sens opposé).
 * <p>
 * Ni {@code TrendConfirmationStrategy} (tendance EMA/ADX/RSI) ni {@code MovementQualificationStrategy}
 * (positionnement dérivés OI/Funding/OBV) ne couvrent cette question : le flux forcé récent est-il
 * en train de se reconstruire dans le même sens, ou le carnet montre-t-il un épuisement ?
 * <p>
 * <b>Strategy {@link StrategyType#CONFIDENCE_MODULATOR}</b>, résolu en même temps que
 * {@link MovementQualificationStrategy} (2026-07-15, comme promis dans la dette documentée à
 * l'étude §4.3 : "à résoudre pour les deux Strategy en même temps... pas en bricolant une solution
 * ad hoc pour une seule") : ce signal ne vote pas sur la direction du marché, il qualifie la
 * fiabilité d'un mouvement déjà voté par les Strategies {@code DIRECTIONAL}. Il n'est donc plus agrégé
 * par {@code StrategyAggregator} avec elles ; son score est converti par
 * {@code MarketOpinionHelper#computeConfidenceModulationFactor} en un facteur qui n'atténue que la
 * confidence finale de l'Opinion (cf. {@code AbstractMarketOpinion#decide}), jamais son score.
 * <p>
 * <b>Seuil de significativité des liquidations</b> (étude §4.2, point le plus ouvert de l'étude) :
 * en <i>relatif</i> au volume récent en devise de cotation ({@code volume × close} sommé sur la
 * même fenêtre {@link #P_PRICE_LOOKBACK_CANDLES} que le calcul de variation de prix), pas en valeur
 * absolue par symbole — un seuil absolu ne serait pas comparable entre BTC et un altcoin, alors que
 * {@code LIQUIDATIONS.values.total} (Coinalyze, en devise de cotation) et {@code volume × close}
 * (bougies) sont dans la même unité, ce qui rend le ratio directement interprétable.
 * <p>
 * Patron de référence : {@link MovementQualificationStrategy} (Strategy à 3 cas explicites,
 * logique pure isolée dans {@link #computeSignal} pour être testable sans réseau).
 */
@Component
public class OrderFlowStrategy extends AbstractStrategy {

    private static final Logger logger = LoggerFactory.getLogger(OrderFlowStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";

    public static final String P_LIQUIDATION_SKEW_THRESHOLD = "liquidationSkewThreshold";
    public static final String P_LIQUIDATION_VOLUME_RATIO_THRESHOLD = "liquidationVolumeRatioThreshold";
    public static final String P_ORDER_BOOK_IMBALANCE_THRESHOLD = "orderBookImbalanceThreshold";
    public static final String P_PRICE_MOVE_THRESHOLD = "priceMoveThreshold";
    public static final String P_PRICE_LOOKBACK_CANDLES = "priceLookbackCandles";
    public static final String P_EXHAUSTION_DAMPENING_FACTOR = "exhaustionDampeningFactor";

    // |liquidationSkew| = |(long-short)/total| doit dépasser 30% pour être considéré comme une
    // cascade "à sens unique" plutôt qu'un flux équilibré des deux côtés.
    private static final double DEFAULT_LIQUIDATION_SKEW_THRESHOLD = 0.3;
    // Liquidations totales (fenêtre LIQUIDATIONS.windowHours) >= 2% du volume récent en devise de
    // cotation (fenêtre priceLookbackCandles) pour être jugées "significatives". Point de départ,
    // pas mesuré empiriquement (cf. javadoc classe).
    private static final double DEFAULT_LIQUIDATION_VOLUME_RATIO_THRESHOLD = 0.02;
    // Même ordre de grandeur que le imbalance normalisé [-1,1] de OrderBookIndicator.
    private static final double DEFAULT_ORDER_BOOK_IMBALANCE_THRESHOLD = 0.15;
    private static final double DEFAULT_PRICE_MOVE_THRESHOLD = 0.02;
    private static final double DEFAULT_PRICE_LOOKBACK_CANDLES = 10.0;
    // Cas "épuisement" (étude §4.2, cas 2) : score jamais inversé franchement, seulement fortement
    // réduit par rapport au cas "flush confirmé".
    private static final double DEFAULT_EXHAUSTION_DAMPENING_FACTOR = 0.3;

    private final IndicatorEngine indicatorEngine;

    public OrderFlowStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {
        if (parameters.getIndicatorParameters().size() != 2) {
            logger.error("Strategy {} needs 2 param (ORDER_BOOK, LIQUIDATIONS)", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 2 param");
        }

        boolean hasError = false;

        Double imbalance = null;
        Double longLiquidated = null;
        Double shortLiquidated = null;
        Double totalLiquidated = null;
        TimeFrame sharedTf = null;

        for (Map.Entry<IndicatorKey, IndicatorParameters> entry : parameters.getIndicatorParameters().entrySet()) {
            IndicatorKey indicatorKey = entry.getKey();
            IndicatorParameters indicatorParams = entry.getValue();

            TimeFrame tf = TimeFrame.valueOf(indicatorParams.getStrings().getOrDefault(P_TIME_FRAME_NAME, "H1"));
            sharedTf = tf;

            IndicatorContext indicatorContext = new IndicatorContext(
                    context.symbol(),
                    tf,
                    context.series().get(tf),
                    null,
                    context.clock()
            );

            IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, indicatorParams);

            if (!snapshot.getResult().isValid() || snapshot.getResult().getValues() == null) {
                logger.error("!---- {} snapshot indicator value considered as INVALID --- Skipping!", indicatorKey.getType());
                hasError = true;
                continue;
            }

            context.addIndicatorValue(indicatorKey, snapshot.getResult());

            Map<String, Double> values = snapshot.getResult().getValues();
            IndicatorType type = indicatorKey.getType();

            switch (type) {
                case ORDER_BOOK -> imbalance = values.get(OrderBookIndicator.V_IMBALANCE);
                case LIQUIDATIONS -> {
                    longLiquidated = values.get(LiquidationsIndicator.V_LONG);
                    shortLiquidated = values.get(LiquidationsIndicator.V_SHORT);
                    totalLiquidated = values.get(LiquidationsIndicator.V_TOTAL);
                }
                default -> logger.warn("{} : unexpected indicator type {} in indicatorParameters", getName(), type);
            }
        }

        if (hasError || imbalance == null || longLiquidated == null || shortLiquidated == null || totalLiquidated == null) {
            return StrategySignal.notValid(getName(), "Missing/invalid ORDER_BOOK or LIQUIDATIONS");
        }

        double liquidationSkewThreshold = parameters.getNumericParams().getOrDefault(P_LIQUIDATION_SKEW_THRESHOLD, DEFAULT_LIQUIDATION_SKEW_THRESHOLD);
        double liquidationVolumeRatioThreshold = parameters.getNumericParams().getOrDefault(P_LIQUIDATION_VOLUME_RATIO_THRESHOLD, DEFAULT_LIQUIDATION_VOLUME_RATIO_THRESHOLD);
        double orderBookImbalanceThreshold = parameters.getNumericParams().getOrDefault(P_ORDER_BOOK_IMBALANCE_THRESHOLD, DEFAULT_ORDER_BOOK_IMBALANCE_THRESHOLD);
        double priceMoveThreshold = parameters.getNumericParams().getOrDefault(P_PRICE_MOVE_THRESHOLD, DEFAULT_PRICE_MOVE_THRESHOLD);
        double exhaustionDampeningFactor = parameters.getNumericParams().getOrDefault(P_EXHAUSTION_DAMPENING_FACTOR, DEFAULT_EXHAUSTION_DAMPENING_FACTOR);

        double priceChangePct = computePriceChangePct(context, sharedTf, parameters);
        double recentQuoteVolume = computeRecentQuoteVolume(context, sharedTf, parameters);

        OrderFlowScore result = computeSignal(
                longLiquidated, shortLiquidated, totalLiquidated, recentQuoteVolume, imbalance, priceChangePct,
                liquidationSkewThreshold, liquidationVolumeRatioThreshold, orderBookImbalanceThreshold,
                priceMoveThreshold, exhaustionDampeningFactor
        );

        logger.debug("{} : liquidations(long={}, short={}, total={}), imbalance={}, priceChangePct={}, "
                        + "recentQuoteVolume={} => score={} ({})",
                getName(), longLiquidated, shortLiquidated, totalLiquidated, imbalance, priceChangePct,
                recentQuoteVolume, result.score(), result.reason());

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(result.score());

        return StrategySignal.builder()
                .strategyName(getName())
                .valid(true)
                .type(confidenceSignal.signal)
                .confidence(confidenceSignal.confidence)
                .score(result.score())
                .reason(result.reason())
                .build();
    }

    /**
     * Variation de prix sur la fenêtre {@code priceLookbackCandles}, même patron que
     * {@code MovementQualificationStrategy#computePriceChangePct} (dégrade proprement à 0.0 si le
     * dataset est absent/trop court, jamais une exception).
     */
    private double computePriceChangePct(MarketContext context, TimeFrame tf, StrategyParameters parameters) {
        MarketDataset dataset = dataset(context, tf);
        if (dataset == null || dataset.getMarketDatas() == null) {
            return 0.0;
        }

        double lookbackParam = parameters.getNumericParams().getOrDefault(P_PRICE_LOOKBACK_CANDLES, DEFAULT_PRICE_LOOKBACK_CANDLES);
        int lookback = (int) lookbackParam;

        List<MarketData> data = dataset.getMarketDatas();
        if (lookback <= 0 || data.size() <= lookback) {
            return 0.0;
        }

        MarketData last = data.getLast();
        MarketData reference = data.get(data.size() - 1 - lookback);
        if (last.getClose() == null || reference.getClose() == null || reference.getClose().signum() == 0) {
            return 0.0;
        }

        return last.getClose().subtract(reference.getClose())
                .divide(reference.getClose(), MathContext.DECIMAL64)
                .doubleValue();
    }

    /**
     * Volume récent en devise de cotation ({@code volume × close} sommé sur la même fenêtre que
     * {@link #computePriceChangePct}) : dénominateur du ratio de significativité des liquidations
     * (étude §4.2) — même unité que {@code LIQUIDATIONS.values.total} (Coinalyze), contrairement au
     * volume brut ({@code MarketData.volume}, en devise de base) qui ne serait pas comparable.
     */
    private double computeRecentQuoteVolume(MarketContext context, TimeFrame tf, StrategyParameters parameters) {
        MarketDataset dataset = dataset(context, tf);
        if (dataset == null || dataset.getMarketDatas() == null) {
            return 0.0;
        }

        double lookbackParam = parameters.getNumericParams().getOrDefault(P_PRICE_LOOKBACK_CANDLES, DEFAULT_PRICE_LOOKBACK_CANDLES);
        int lookback = (int) lookbackParam;

        List<MarketData> data = dataset.getMarketDatas();
        if (lookback <= 0 || data.isEmpty()) {
            return 0.0;
        }

        int from = Math.max(0, data.size() - lookback);
        double total = 0.0;
        for (int i = from; i < data.size(); i++) {
            MarketData candle = data.get(i);
            if (candle.getVolume() != null && candle.getClose() != null) {
                total += candle.getVolume().doubleValue() * candle.getClose().doubleValue();
            }
        }
        return total;
    }

    private static MarketDataset dataset(MarketContext context, TimeFrame tf) {
        if (tf == null || context.series() == null) {
            return null;
        }
        return context.series().get(tf);
    }

    /**
     * Logique pure de score, isolée de l'exécution des indicateurs pour être testable en unitaire
     * (patron {@code MovementQualificationStrategy.computeSignal}).
     * <p>
     * <b>Point de départ, pas une formule figée</b> (mêmes réserves que {@code MovementQualificationStrategy}) :
     * 3 cas — cascade non significative ou incohérente avec le prix récent (neutre), cascade
     * confirmée par le prix et le carnet (continuation), cascade confirmée par le prix mais
     * contredite par le carnet (épuisement, score atténué).
     */
    static OrderFlowScore computeSignal(
            double longLiquidated, double shortLiquidated, double totalLiquidated,
            double recentQuoteVolume,
            double imbalance,
            double priceChangePct,
            double liquidationSkewThreshold,
            double liquidationVolumeRatioThreshold,
            double orderBookImbalanceThreshold,
            double priceMoveThreshold,
            double exhaustionDampeningFactor
    ) {
        double liquidationSkew = totalLiquidated == 0 ? 0.0 : (longLiquidated - shortLiquidated) / totalLiquidated;
        double liquidationVolumeRatio = recentQuoteVolume <= 0 ? 0.0 : totalLiquidated / recentQuoteVolume;
        boolean markedPriceMove = Math.abs(priceChangePct) >= priceMoveThreshold;

        boolean cascadeSignificant = Math.abs(liquidationSkew) >= liquidationSkewThreshold
                && liquidationVolumeRatio >= liquidationVolumeRatioThreshold
                && markedPriceMove;

        if (!cascadeSignificant) {
            return new OrderFlowScore(0.0, String.format(
                    "aucune cascade de liquidations significative sur la fenêtre (liquidationSkew=%.4f, liquidationVolumeRatio=%.4f, markedPriceMove=%b)",
                    liquidationSkew, liquidationVolumeRatio, markedPriceMove));
        }

        // longs liquidés en masse (skew > 0) -> pression baissière déjà à l'oeuvre, et inversement.
        double cascadeDirection = liquidationSkew > 0 ? -1.0 : 1.0;
        double priceDirection = Math.signum(priceChangePct);

        if (priceDirection != cascadeDirection) {
            // Fenêtres différentes par construction (LIQUIDATIONS.windowHours vs
            // priceLookbackCandles, cf. javadoc classe) : pas de conviction si elles divergent,
            // neutre plutôt qu'un pari.
            return new OrderFlowScore(0.0, String.format(
                    "cascade détectée (liquidationSkew=%.4f) mais direction du prix récent incohérente (priceChangePct=%.4f) : neutre",
                    liquidationSkew, priceChangePct));
        }

        double bookSign = Math.signum(imbalance);
        boolean bookSignificant = Math.abs(imbalance) >= orderBookImbalanceThreshold;
        boolean bookOpposed = bookSignificant && bookSign == -cascadeDirection;

        double magnitude = clamp01(0.5 * Math.abs(liquidationSkew) + 0.5 * Math.abs(imbalance));

        if (bookOpposed) {
            double score = cascadeDirection * magnitude * exhaustionDampeningFactor;
            return new OrderFlowScore(score, String.format(
                    "flush confirmé par le prix (liquidationSkew=%.4f) mais carnet opposé (imbalance=%.4f) : épuisement possible, signal atténué",
                    liquidationSkew, imbalance));
        }

        double score = cascadeDirection * magnitude;
        return new OrderFlowScore(score, String.format(
                "flush confirmé par le prix et le carnet (liquidationSkew=%.4f, imbalance=%.4f)",
                liquidationSkew, imbalance));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.clamp(v, 0.0, 1.0);
    }

    @Override
    public Set<StrategyType> getType() {
        return Set.of(StrategyType.CONFIDENCE_MODULATOR);
    }

    @Override
    public boolean accepts(StrategyParameters parameters) {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = parameters.getIndicatorParameters();
        if (indicatorParameters == null || indicatorParameters.size() != 2) {
            return false;
        }

        long orderBookCount = indicatorParameters.values().stream().filter(p -> p.getIndicatorType() == IndicatorType.ORDER_BOOK).count();
        long liquidationsCount = indicatorParameters.values().stream().filter(p -> p.getIndicatorType() == IndicatorType.LIQUIDATIONS).count();

        return orderBookCount == 1 && liquidationsCount == 1;
    }

    record OrderFlowScore(double score, String reason) {
    }
}
