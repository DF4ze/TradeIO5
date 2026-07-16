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
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.EtfFlowIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flux ETF institutionnel comme modulateur de confidence (docs/etude-branchement-etf-flow-confidence-modulator.md,
 * 5 décisions validées avec Clem le 2026-07-16). Compare le flux net ETF du jour (SoSoValue, USD
 * brut) au mouvement de prix récent : un flux qui confirme le mouvement (ou un flux/mouvement non
 * significatif) n'atténue jamais ; un flux qui le contredit (rally sans soutien institutionnel, ou
 * inversement) atténue — signal de fragilité, jamais de signal directionnel.
 * <p>
 * <b>Strategy {@link StrategyType#CONFIDENCE_MODULATOR}</b>, même mécanisme que
 * {@link MovementQualificationStrategy}/{@link OrderFlowStrategy} : ne vote jamais sur la direction,
 * son score est converti par {@code MarketOpinionHelper#computeConfidenceModulationFactor} en un
 * facteur qui n'atténue que la confidence finale de l'Opinion.
 * <p>
 * <b>Décision (étude §9.4)</b> : volontairement <b>pas branchée par défaut</b> dans
 * {@code DefaultMarketOpinion} dans ce lot — accessible uniquement en ad hoc via
 * {@code evaluate_strategy}/{@code get_opinion} (MCP), le temps d'observer son comportement en
 * réel (source de données migrée le jour même, seuils non calibrés). À promouvoir en branchement par
 * défaut plus tard, même progression que {@link MovementQualificationStrategy} en son temps.
 * <p>
 * <b>Décision (étude §9.5)</b> : restriction à une liste blanche explicite de symboles
 * ({@link #ACCEPTED_SYMBOLS}) plutôt qu'un préfixe {@code BTC*}/{@code ETH*} — un préfixe capturerait
 * aussi des paires croisées comme {@code ETHBTC}, où le prix n'est pas coté en USD et n'est donc pas
 * comparable au flux ETF (USD brut). Cette restriction ne peut pas passer par
 * {@link #accepts(StrategyParameters)} (qui n'a pas accès au symbole, cf. étude §2.1) : elle se fait
 * ici, dans {@link #evaluate}, avec repli propre en {@link StrategySignal#notValid} pour tout
 * symbole hors périmètre — traduit par {@code StrategyConfidenceModulator} en
 * {@code ModulationResult(applied=false, factor=1.0)}, donc ignoré sans jamais atténuer.
 * <p>
 * <b>Décision (étude §2.2)</b> : {@code asset} n'est <b>jamais</b> lu depuis les
 * {@code IndicatorParameters} fournies par l'appelant (qui sont construites une fois, réutilisables
 * pour n'importe quel symbole, cf. {@code StrategyParametersFactory}) — il est recalculé à chaque
 * appel depuis {@code context.symbol()}, même piège que le bug {@code get_indicator} corrigé le
 * 2026-07-16 (sinon ETH retomberait silencieusement sur BTC).
 * <p>
 * <b>Décision (étude §9.3)</b> : tourne sur {@code D1} avec un lookback court (1 bougie par défaut),
 * pas {@code H1}/10 bougies comme {@link MovementQualificationStrategy}/{@link OrderFlowStrategy} —
 * {@code ETF_FLOW} ne se met à jour qu'une fois par jour (post-clôture US), comparer à un mouvement
 * de prix H1 serait un décalage d'échelle.
 * <p>
 * Patron de référence : {@link OrderFlowStrategy} (3 cas — cohérent/divergent/neutre — plus proche
 * ici que les 3 cas spécifiques OI/funding de {@code MovementQualificationStrategy}). Simplification
 * assumée par rapport à {@code OrderFlowStrategy} : les cas "cohérent" et "neutre" sont fusionnés
 * (même {@code score=0.0}, seul le {@code reason} diffère) — {@code computeConfidenceModulationFactor}
 * ne bonifie jamais un score positif, calculer une magnitude positive n'aurait aucun effet observable.
 */
@Component
public class EtfFlowConfidenceStrategy extends AbstractStrategy {

    private static final Logger logger = LoggerFactory.getLogger(EtfFlowConfidenceStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";
    public static final String P_FLOW_SIGNIFICANCE_THRESHOLD_USD = "flowSignificanceThresholdUsd";
    public static final String P_MAGNITUDE_SCALE_FACTOR = "magnitudeScaleFactor";
    public static final String P_PRICE_MOVE_THRESHOLD = "priceMoveThreshold";
    public static final String P_PRICE_LOOKBACK_CANDLES = "priceLookbackCandles";

    // Ordre de grandeur d'un jour "calme" côté flux ETF BTC/ETH (USD brut, cf. SosoValueEtfFlowClient
    // — pas de conversion "millions" contrairement à l'ancien historique Farside) : en dessous, le
    // flux est considéré comme du bruit, pas un signal exploitable. Point de départ non calibré sur
    // données réelles (même réserve assumée que OrderFlowStrategy/MovementQualificationStrategy à
    // leur création), à ajuster une fois assez d'historique SoSoValue accumulé.
    private static final double DEFAULT_FLOW_SIGNIFICANCE_THRESHOLD_USD = 50_000_000.0;
    // Sortie/entrée nette qui atteint 3x le seuil de significativité => atténuation maximale (score=-1).
    private static final double DEFAULT_MAGNITUDE_SCALE_FACTOR = 3.0;
    private static final double DEFAULT_PRICE_MOVE_THRESHOLD = 0.02;
    private static final double DEFAULT_PRICE_LOOKBACK_CANDLES = 1.0;

    private static final Map<String, EtfFlowAsset> ACCEPTED_SYMBOLS = Map.of(
            "BTCUSDT", EtfFlowAsset.BTC,
            "ETHUSDT", EtfFlowAsset.ETH
    );

    private final IndicatorEngine indicatorEngine;

    public EtfFlowConfidenceStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {
        if (parameters.getIndicatorParameters().size() != 1) {
            logger.error("Strategy {} needs 1 param (ETF_FLOW)", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 1 param");
        }

        Map.Entry<IndicatorKey, IndicatorParameters> entry =
                parameters.getIndicatorParameters().entrySet().iterator().next();
        IndicatorKey indicatorKey = entry.getKey();
        IndicatorParameters suppliedParams = entry.getValue();

        if (indicatorKey.getType() != IndicatorType.ETF_FLOW) {
            logger.error("{} : unexpected indicator type {} in indicatorParameters", getName(), indicatorKey.getType());
            return StrategySignal.notValid(getName(), "Missing/invalid ETF_FLOW");
        }

        EtfFlowAsset asset = ACCEPTED_SYMBOLS.get(context.symbol());
        if (asset == null) {
            return StrategySignal.notValid(getName(),
                    "symbole " + context.symbol() + " hors périmètre ETF_FLOW (BTCUSDT/ETHUSDT uniquement)");
        }

        TimeFrame tf = TimeFrame.valueOf(suppliedParams.getStrings().getOrDefault(P_TIME_FRAME_NAME, "D1"));

        // Ne jamais faire confiance à un "asset" pré-rempli dans les IndicatorParameters fournies :
        // reconstruit ici depuis context.symbol() (cf. javadoc de classe, décision étude §2.2).
        IndicatorParameters etfFlowParams = IndicatorParameters.builder()
                .indicatorType(IndicatorType.ETF_FLOW)
                .numerics(suppliedParams.getNumerics())
                .strings(Map.of(EtfFlowIndicator.P_ASSET, asset.name()))
                .booleans(suppliedParams.getBooleans())
                .credential(suppliedParams.getCredential())
                .build();

        IndicatorContext indicatorContext = new IndicatorContext(
                context.symbol(), tf, context.series().get(tf), null, context.clock());

        IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, etfFlowParams);

        if (!snapshot.getResult().isValid() || snapshot.getResult().getValue() == null) {
            logger.error("!---- {} snapshot indicator value considered as INVALID --- Skipping!", indicatorKey.getType());
            return StrategySignal.notValid(getName(), "Missing/invalid ETF_FLOW");
        }

        context.addIndicatorValue(indicatorKey, snapshot.getResult());
        double total = snapshot.getResult().getValue();

        double priceChangePct = computePriceChangePct(context, tf, parameters);

        double flowSignificanceThresholdUsd = parameters.getNumericParams()
                .getOrDefault(P_FLOW_SIGNIFICANCE_THRESHOLD_USD, DEFAULT_FLOW_SIGNIFICANCE_THRESHOLD_USD);
        double magnitudeScaleFactor = parameters.getNumericParams()
                .getOrDefault(P_MAGNITUDE_SCALE_FACTOR, DEFAULT_MAGNITUDE_SCALE_FACTOR);
        double priceMoveThreshold = parameters.getNumericParams()
                .getOrDefault(P_PRICE_MOVE_THRESHOLD, DEFAULT_PRICE_MOVE_THRESHOLD);

        EtfFlowScore result = computeSignal(
                total, priceChangePct, flowSignificanceThresholdUsd, magnitudeScaleFactor, priceMoveThreshold);

        logger.debug("{} : total={}, priceChangePct={} => score={} ({})",
                getName(), total, priceChangePct, result.score(), result.reason());

        MarketOpinionHelper.ConfidenceSignal confidenceSignal =
                MarketOpinionHelper.scoreToConfidenceAndSignalType(result.score());

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
     * {@code MovementQualificationStrategy#computePriceChangePct}/{@code OrderFlowStrategy#computePriceChangePct}
     * (dégrade proprement à 0.0 si le dataset est absent/trop court, jamais une exception) — dupliqué
     * à l'identique plutôt que factorisé, cf. note "hors scope" de l'étude §7 (candidat à extraire si
     * un 4e modulateur du même genre apparaît).
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
                .divide(reference.getClose(), MathContext.DECIMAL64)
                .doubleValue();
    }

    /**
     * Logique pure de score, isolée de l'exécution des indicateurs pour être testable en unitaire
     * (patron {@code OrderFlowStrategy.computeSignal}).
     * <p>
     * <b>Point de départ, pas une formule figée</b> (mêmes réserves que {@code MovementQualificationStrategy}/
     * {@code OrderFlowStrategy}) : 2 cas seulement (divergent, sinon neutre — cf. javadoc de classe
     * pour la fusion cohérent/neutre).
     */
    static EtfFlowScore computeSignal(
            double total,
            double priceChangePct,
            double flowSignificanceThresholdUsd,
            double magnitudeScaleFactor,
            double priceMoveThreshold
    ) {
        boolean markedPriceMove = Math.abs(priceChangePct) >= priceMoveThreshold;
        boolean significantFlow = Math.abs(total) >= flowSignificanceThresholdUsd;

        if (!markedPriceMove || !significantFlow) {
            return new EtfFlowScore(0.0, String.format(
                    "pas de mouvement de prix marqué ou flux ETF non significatif (total=%.0f, priceChangePct=%.4f)",
                    total, priceChangePct));
        }

        double flowDirection = Math.signum(total);
        double priceDirection = Math.signum(priceChangePct);

        if (flowDirection == priceDirection) {
            return new EtfFlowScore(0.0, String.format(
                    "flux ETF cohérent avec le mouvement de prix (total=%.0f, priceChangePct=%.4f), aucune atténuation",
                    total, priceChangePct));
        }

        double magnitude = clamp01(Math.abs(total) / (flowSignificanceThresholdUsd * magnitudeScaleFactor));
        double score = -magnitude;
        return new EtfFlowScore(score, String.format(
                "mouvement de prix non soutenu par le flux ETF institutionnel, divergence (total=%.0f, priceChangePct=%.4f)",
                total, priceChangePct));
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
        if (indicatorParameters == null || indicatorParameters.size() != 1) {
            return false;
        }

        long etfFlowCount = indicatorParameters.values().stream()
                .filter(p -> p.getIndicatorType() == IndicatorType.ETF_FLOW)
                .count();

        return etfFlowCount == 1;
    }

    record EtfFlowScore(double score, String reason) {
    }
}
