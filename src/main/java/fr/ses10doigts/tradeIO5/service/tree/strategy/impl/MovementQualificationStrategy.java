package fr.ses10doigts.tradeIO5.service.tree.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
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
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.OpenInterestIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qualification de mouvement (étude "indicateurs-macro-externes" §14 item H, prompt
 * d'implémentation Lot 2) : combine Open Interest (delta current/previous), Funding Rate (niveau
 * absolu) et OBV (signe) pour distinguer une cascade de liquidations d'un mouvement de conviction
 * spot, ou détecter un sur-effet-de-levier en construction.
 * <p>
 * <b>Limite déjà connue et acceptée pour ce lot</b> (signalée dans l'étude "extension-risk-macro-
 * external" §4.1, cf. prompt d'implémentation) : cette Strategy est agrégée par
 * {@code StrategyAggregator} de la même façon qu'une Strategy directionnelle classique
 * ({@code ENTRY}), alors qu'elle joue conceptuellement un rôle de <b>modulateur de confiance</b>
 * plutôt que de générateur de signal indépendant (dans le même esprit que Fear &amp; Greed vis-à-vis
 * d'un signal technique). Le mécanisme de modulation dédié n'existe pas encore dans le code — cette
 * Strategy est traitée comme {@link StrategyType#ENTRY} classique en attendant, comme une
 * simplification assumée pour ce lot.
 * <p>
 * Patron de référence : {@link TrendConfirmationStrategy} (Strategy qui combine plusieurs
 * {@code IndicatorType}, discrimination par {@code IndicatorKey.getType()}).
 */
@Component
public class MovementQualificationStrategy extends AbstractStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MovementQualificationStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";

    // Seuils propres à la Strategy (StrategyParameters.numericParams), voir patron
    // TrendConfirmationStrategy.P_ADX_LOW_THRESHOLD/DEFAULT_ADX_LOW_THRESHOLD.
    public static final String P_OI_DELTA_CASCADE_THRESHOLD = "oiDeltaCascadeThreshold";
    public static final String P_OI_DELTA_BUILDUP_THRESHOLD = "oiDeltaBuildupThreshold";
    public static final String P_FUNDING_LOW_THRESHOLD = "fundingLowThreshold";
    public static final String P_FUNDING_HIGH_THRESHOLD = "fundingHighThreshold";
    public static final String P_FUNDING_BUILDUP_SIGNAL_THRESHOLD = "fundingBuildupSignalThreshold";
    public static final String P_FUNDING_NEUTRAL_BAND = "fundingNeutralBand";
    public static final String P_PRICE_MOVE_THRESHOLD = "priceMoveThreshold";
    public static final String P_PRICE_LOOKBACK_CANDLES = "priceLookbackCandles";

    // oiDelta <= -10% : signature d'une cascade de liquidations (étude §4.1).
    private static final double DEFAULT_OI_DELTA_CASCADE_THRESHOLD = -0.10;
    // oiDelta >= +10% pendant une hausse de prix + funding fortement positif : sur-effet-de-levier
    // long en construction.
    private static final double DEFAULT_OI_DELTA_BUILDUP_THRESHOLD = 0.10;
    // Funding : zone "neutre" en dessous de 0.05%, "extrême" à partir de 1% (mêmes ordres de
    // grandeur que les seuils habituels de funding rate perpétuel 8h).
    private static final double DEFAULT_FUNDING_LOW_THRESHOLD = 0.0005;
    private static final double DEFAULT_FUNDING_HIGH_THRESHOLD = 0.01;
    // fundingSignal (normalisé [-1,1]) doit être au moins à 60% de l'extrême positif pour
    // contribuer au cas "sur-effet-de-levier en construction".
    private static final double DEFAULT_FUNDING_BUILDUP_SIGNAL_THRESHOLD = 0.6;
    // fundingSignal considéré "neutre" pour le cas "conviction spot" si sa valeur absolue reste
    // sous cette borne.
    private static final double DEFAULT_FUNDING_NEUTRAL_BAND = 0.3;
    // Mouvement de prix considéré "marqué" à partir de 2% sur la fenêtre de lookback.
    private static final double DEFAULT_PRICE_MOVE_THRESHOLD = 0.02;
    private static final double DEFAULT_PRICE_LOOKBACK_CANDLES = 10.0;

    private final IndicatorEngine indicatorEngine;

    public MovementQualificationStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {
        if (parameters.getIndicatorParameters().size() != 3) {
            logger.error("Strategy {} needs 3 param (OPEN_INTEREST, FUNDING_RATE, OBV)", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 3 param");
        }

        boolean hasError = false;

        Double oiCurrent = null;
        Double oiPrevious = null;
        Double funding = null;
        Double obv = null;
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

            if (!snapshot.getResult().isValid()) {
                logger.error("!---- {} snapshot indicator value considered as INVALID --- Skipping!", indicatorKey.getType());
                hasError = true;
                continue;
            }

            context.addIndicatorValue(indicatorKey, snapshot.getResult());

            IndicatorResult result = snapshot.getResult();
            IndicatorType type = indicatorKey.getType();

            switch (type) {
                case OPEN_INTEREST -> {
                    Map<String, Double> values = result.getValues();
                    if (values == null
                            || values.get(OpenInterestIndicator.V_CURRENT) == null
                            || values.get(OpenInterestIndicator.V_PREVIOUS) == null) {
                        logger.error("{} : OPEN_INTEREST result missing current/previous in values", getName());
                        hasError = true;
                    } else {
                        oiCurrent = values.get(OpenInterestIndicator.V_CURRENT);
                        oiPrevious = values.get(OpenInterestIndicator.V_PREVIOUS);
                    }
                }
                case FUNDING_RATE -> funding = result.getValue();
                case OBV -> obv = result.getValue();
                default -> logger.warn("{} : unexpected indicator type {} in indicatorParameters", getName(), type);
            }
        }

        if (hasError || oiCurrent == null || oiPrevious == null || funding == null || obv == null) {
            return StrategySignal.notValid(getName(), "Missing/invalid OPEN_INTEREST, FUNDING_RATE or OBV");
        }

        double priceChangePct = computePriceChangePct(context, sharedTf, parameters);

        double oiDeltaCascadeThreshold = parameters.getNumericParams().getOrDefault(P_OI_DELTA_CASCADE_THRESHOLD, DEFAULT_OI_DELTA_CASCADE_THRESHOLD);
        double oiDeltaBuildupThreshold = parameters.getNumericParams().getOrDefault(P_OI_DELTA_BUILDUP_THRESHOLD, DEFAULT_OI_DELTA_BUILDUP_THRESHOLD);
        double fundingLowThreshold = parameters.getNumericParams().getOrDefault(P_FUNDING_LOW_THRESHOLD, DEFAULT_FUNDING_LOW_THRESHOLD);
        double fundingHighThreshold = parameters.getNumericParams().getOrDefault(P_FUNDING_HIGH_THRESHOLD, DEFAULT_FUNDING_HIGH_THRESHOLD);
        double fundingBuildupSignalThreshold = parameters.getNumericParams().getOrDefault(P_FUNDING_BUILDUP_SIGNAL_THRESHOLD, DEFAULT_FUNDING_BUILDUP_SIGNAL_THRESHOLD);
        double fundingNeutralBand = parameters.getNumericParams().getOrDefault(P_FUNDING_NEUTRAL_BAND, DEFAULT_FUNDING_NEUTRAL_BAND);
        double priceMoveThreshold = parameters.getNumericParams().getOrDefault(P_PRICE_MOVE_THRESHOLD, DEFAULT_PRICE_MOVE_THRESHOLD);

        MovementScore movementScore = computeSignal(
                oiCurrent, oiPrevious, funding, obv, priceChangePct,
                oiDeltaCascadeThreshold, oiDeltaBuildupThreshold,
                fundingLowThreshold, fundingHighThreshold, fundingBuildupSignalThreshold, fundingNeutralBand,
                priceMoveThreshold
        );

        logger.debug("{} : oiCurrent={}, oiPrevious={}, funding={}, obv={}, priceChangePct={} => score={} ({})",
                getName(), oiCurrent, oiPrevious, funding, obv, priceChangePct, movementScore.score(), movementScore.reason());

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(movementScore.score());

        return StrategySignal.builder()
                .strategyName(getName())
                .valid(true)
                .type(confidenceSignal.signal)
                .confidence(confidenceSignal.confidence)
                .score(movementScore.score())
                .reason(movementScore.reason())
                .build();
    }

    /**
     * Variation de prix sur la fenêtre {@code priceLookbackCandles} (défaut
     * {@link #DEFAULT_PRICE_LOOKBACK_CANDLES}) du {@code MarketDataset} partagé par les 3
     * indicateurs, utilisée pour juger si un mouvement de prix est "marqué" (cas cascade). Retourne
     * {@code 0.0} (mouvement neutre, jamais considéré "marqué") si le dataset est absent/trop court
     * plutôt que de lancer une exception — dégrade proprement le cas "cascade" (qui ne se
     * déclenchera simplement jamais) sans invalider OI/funding/OBV par ailleurs valides.
     */
    private double computePriceChangePct(MarketContext context, TimeFrame tf, StrategyParameters parameters) {
        if (tf == null || context.series() == null) {
            return 0.0;
        }
        MarketDataset dataset = context.series().get(tf);
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
                .divide(reference.getClose(), java.math.MathContext.DECIMAL64)
                .doubleValue();
    }

    /**
     * Logique pure de score, isolée de l'exécution des indicateurs pour être testable en unitaire
     * (patron {@code TrendConfirmationStrategy}, qui ne l'isole pas explicitement, mais
     * {@code LiquidationsIndicator.sumHistory}/{@code DefiLlamaStablecoinClient.aggregate} pour le
     * principe général de logique pure testable côté ce projet).
     * <p>
     * <b>Point de départ, pas une formule figée</b> (cf. prompt d'implémentation, item H, "à
     * ajuster empiriquement") : 3 cas mutuellement exclusifs par construction (cascade = oiDelta
     * très négatif, buildup = oiDelta très positif), sinon neutre.
     */
    static MovementScore computeSignal(
            double oiCurrent, double oiPrevious,
            double funding,
            double obv,
            double priceChangePct,
            double oiDeltaCascadeThreshold,
            double oiDeltaBuildupThreshold,
            double fundingLowThreshold,
            double fundingHighThreshold,
            double fundingBuildupSignalThreshold,
            double fundingNeutralBand,
            double priceMoveThreshold
    ) {
        double oiDelta = oiPrevious == 0 ? 0.0 : (oiCurrent - oiPrevious) / oiPrevious;
        double fundingSignal = normalizeFundingSignal(funding, fundingLowThreshold, fundingHighThreshold);
        // OBV n'expose qu'une valeur cumulative ponctuelle (pas de moyenne mobile ni de pente
        // calculable à partir d'un seul IndicatorResult, cf. ObvIndicator relu avant cet item) :
        // son signe est la seule information directionnelle exploitable sans deviner un champ
        // inexistant (cf. prompt d'implémentation, item H, "ne pas deviner un champ qui n'existe
        // pas dans ObvIndicator").
        double volumeConfirmation = Math.signum(obv);
        boolean markedPriceMove = Math.abs(priceChangePct) >= priceMoveThreshold;

        // Cas 1 : cascade de liquidations (regarde en arrière) -----------------------------------
        if (oiDelta <= oiDeltaCascadeThreshold && markedPriceMove) {
            double magnitude = clamp01(Math.abs(oiDelta) / Math.abs(oiDeltaCascadeThreshold));
            double score = -magnitude;
            return new MovementScore(score, String.format(
                    "cascade de liquidations : OI en forte baisse (oiDelta=%.4f) pendant un mouvement de prix marqué (%.4f), mouvement probablement peu durable",
                    oiDelta, priceChangePct));
        }

        // Cas 3 : sur-effet-de-levier en construction (regarde en avant, risque à venir) ---------
        if (fundingSignal >= fundingBuildupSignalThreshold && oiDelta >= oiDeltaBuildupThreshold && priceChangePct > 0) {
            double magnitude = clamp01(Math.min(fundingSignal, oiDelta / oiDeltaBuildupThreshold));
            double score = -magnitude;
            return new MovementScore(score, String.format(
                    "sur-effet-de-levier long en construction : funding fortement positif (fundingSignal=%.2f) et OI en forte hausse (oiDelta=%.4f) pendant une hausse de prix, risque de retournement violent à venir",
                    fundingSignal, oiDelta));
        }

        // Cas 2 : conviction spot -----------------------------------------------------------------
        if (oiDelta >= 0 && Math.abs(fundingSignal) <= fundingNeutralBand && volumeConfirmation > 0) {
            double oiComponent = clamp01(oiDelta / Math.max(oiDeltaBuildupThreshold, 1e-9));
            double score = clamp01(0.5 + 0.5 * oiComponent);
            return new MovementScore(score, String.format(
                    "mouvement de conviction, spot : OI stable/en hausse (oiDelta=%.4f), funding neutre (fundingSignal=%.2f), volume confirmé",
                    oiDelta, fundingSignal));
        }

        return new MovementScore(0.0, "aucun pattern net détecté (OI/funding/volume ne correspondent à aucun des 3 cas typés)");
    }

    private static double normalizeFundingSignal(double funding, double lowThreshold, double highThreshold) {
        double sign = Math.signum(funding);
        double absFunding = Math.abs(funding);

        if (highThreshold <= lowThreshold) {
            return absFunding >= highThreshold ? sign : 0.0;
        }
        if (absFunding <= lowThreshold) {
            return 0.0;
        }
        if (absFunding >= highThreshold) {
            return sign;
        }
        double magnitude = (absFunding - lowThreshold) / (highThreshold - lowThreshold);
        return sign * magnitude;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.clamp(v, 0.0, 1.0);
    }

    @Override
    public Set<StrategyType> getType() {
        return Set.of(StrategyType.ENTRY);
    }

    @Override
    public boolean accepts(StrategyParameters parameters) {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = parameters.getIndicatorParameters();
        if (indicatorParameters == null || indicatorParameters.size() != 3) {
            return false;
        }

        long oiCount = indicatorParameters.values().stream().filter(p -> p.getIndicatorType() == IndicatorType.OPEN_INTEREST).count();
        long fundingCount = indicatorParameters.values().stream().filter(p -> p.getIndicatorType() == IndicatorType.FUNDING_RATE).count();
        long obvCount = indicatorParameters.values().stream().filter(p -> p.getIndicatorType() == IndicatorType.OBV).count();

        return oiCount == 1 && fundingCount == 1 && obvCount == 1;
    }

    record MovementScore(double score, String reason) {
    }
}
