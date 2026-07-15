package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.DxyIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.Sp500Indicator;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.ConfidenceModulation;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.ModulationResult;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.StalenessModulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opinion {@code MACRO} (étude "nouvelles-opinions-indicateurs-non-branches" §1/§2) : toile de fond
 * TradFi (DXY + SP500 + NASDAQ), premier consommateur du scope {@code MACRO} — jusqu'ici déclaré
 * dans {@link OpinionScope} mais jamais utilisé (voir étude "extension-risk-macro-external" §3.3,
 * qui recommandait d'attendre un second indicateur macro-économique réel avant de trancher la
 * séparation {@code GLOBAL}/{@code MACRO} : c'est désormais le cas).
 * <p>
 * Implémente {@link MarketOpinion} directement (même patron que {@link GlobalMarketOpinion}), pas
 * {@code AbstractMarketOpinion} : la combinaison DXY/SP500/NASDAQ ci-dessous n'est pas une
 * {@code Strategy} (pas de {@code MarketContext}/bougies nécessaires, valeurs externes uniques).
 * <p>
 * <b>Risk appetite</b> : dollar fort (DXY en hausse) traité comme risk-off (score négatif,
 * poids {@link #P_DXY_WEIGHT}) ; SP500/NASDAQ en hausse traités comme risk-on (score positif),
 * NASDAQ pondéré plus haut ({@link #P_NASDAQ_WEIGHT} &gt; {@link #P_SP500_WEIGHT}) : beta
 * historiquement plus proche de la crypto (tech/risk assets) que le SP500 large marché.
 * <p>
 * <b>Tout signal invalide invalide toute l'Opinion</b> (même philosophie que
 * {@code StrategyAggregator}/{@code DxyIndicator} : jamais de moyenne partielle ni de valeur par
 * défaut arbitraire) : si un seul des 3 indicateurs est invalide, ou si {@code values.previous} est
 * absent pour l'un d'eux (delta non calculable), aucun {@code OpinionEvent} n'est publié.
 * <p>
 * <b>Fraîcheur</b> : SP500/NASDAQ ne tradent pas 24/7 — une confidence calculée un week-end sur la
 * clôture de vendredi soir est atténuée (jamais annulée brutalement) via
 * {@link MarketOpinionHelper#computeStalenessDampening}, le facteur le plus conservateur des deux
 * étant retenu (DXY, forex 24/5, n'a pas ce problème).
 * <p>
 * <b>Décision de conception assumée</b> (étude §2.4, pas tranchée définitivement) : cette Opinion
 * publie un {@code OpinionEvent} <b>sans symbole</b> (signal d'ambiance, même patron que
 * {@link GlobalMarketOpinion}) — {@code DecisionEngine.isUnanimousAcrossScopes} l'exclut donc de
 * l'arbitrage inter-scopes par symbole (court-circuit sur {@code event.getSymbol().isEmpty()}).
 * Évolution possible plus tard vers une exécution par symbole si la conviction "MACRO doit pouvoir
 * bloquer un LOCAL bullish" est validée sur des cas réels ({@code ScenarioKey} porte déjà le
 * {@code scope}, donc pas de collision silencieuse avec {@code LOCAL}/{@code EXTERNAL} le jour où
 * ce changement est fait).
 */
@Component
public class MacroMarketOpinion implements MarketOpinion {
    private static final Logger logger = LoggerFactory.getLogger(MacroMarketOpinion.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";
    public static final String P_DXY_WEIGHT = "dxyWeight";
    public static final String P_SP500_WEIGHT = "sp500Weight";
    public static final String P_NASDAQ_WEIGHT = "nasdaqWeight";
    public static final String P_DXY_DAILY_SCALE = "dxyDailyScale";
    public static final String P_EQUITY_DAILY_SCALE = "equityDailyScale";
    public static final String P_STALE_QUOTE_HOURS = "staleQuoteHours";

    // NASDAQ pondéré plus haut que SP500 (beta historiquement plus proche de la crypto), DXY à
    // poids comparable à SP500 (cf. javadoc classe) - somme = 1.0, pas imposée mais cohérente.
    private static final double DEFAULT_DXY_WEIGHT = 0.3;
    private static final double DEFAULT_SP500_WEIGHT = 0.3;
    private static final double DEFAULT_NASDAQ_WEIGHT = 0.4;
    // DXY bouge historiquement moins que les indices actions en une journée (étude §2.2) : échelle
    // de saturation plus petite. Valeurs par défaut proposées, pas mesurées empiriquement, à
    // calibrer.
    private static final double DEFAULT_DXY_DAILY_SCALE = 0.005;
    private static final double DEFAULT_EQUITY_DAILY_SCALE = 0.01;
    // Au-delà de cette ancienneté (dernière transaction), une quote SP500/NASDAQ est jugée figée
    // (week-end/marché fermé) et voit sa confidence atténuée (étude §2.3).
    private static final double DEFAULT_STALE_QUOTE_HOURS = 18.0;
    private static final TimeFrame DEFAULT_TIME_FRAME = TimeFrame.H1;

    private final IndicatorEngine indicatorEngine;
    private final IndicatorCredentialResolver credentialResolver;

    @Autowired
    private EventBus eventBus;

    public MacroMarketOpinion(IndicatorEngine indicatorEngine, IndicatorCredentialResolver credentialResolver) {
        this.indicatorEngine = indicatorEngine;
        this.credentialResolver = credentialResolver;
    }

    @Override
    public OpinionScope getScope() {
        return OpinionScope.MACRO;
    }

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(MarketOpinionParameters parameters) {
        // DXY/SP500/NASDAQ ne lisent jamais context.marketDataset() (valeurs externes uniques) :
        // aucune bougie à pré-charger, même raisonnement que GlobalMarketOpinion.
        return Map.of();
    }

    @Override
    public void decide(OpinionContext context, MarketOpinionParameters parameters) {
        TimeFrame tf = resolveTimeFrame(parameters);
        double dxyWeight = get(parameters, P_DXY_WEIGHT, DEFAULT_DXY_WEIGHT);
        double sp500Weight = get(parameters, P_SP500_WEIGHT, DEFAULT_SP500_WEIGHT);
        double nasdaqWeight = get(parameters, P_NASDAQ_WEIGHT, DEFAULT_NASDAQ_WEIGHT);
        double dxyScale = get(parameters, P_DXY_DAILY_SCALE, DEFAULT_DXY_DAILY_SCALE);
        double equityScale = get(parameters, P_EQUITY_DAILY_SCALE, DEFAULT_EQUITY_DAILY_SCALE);
        double staleQuoteHours = get(parameters, P_STALE_QUOTE_HOURS, DEFAULT_STALE_QUOTE_HOURS);

        String symbol = context.marketContext() != null ? context.marketContext().symbol() : null;
        IndicatorContext indicatorContext = new IndicatorContext(symbol, tf, null, Map.of(), context.clock());

        IndicatorSnapshot dxySnapshot = execute(indicatorContext, IndicatorType.DXY);
        IndicatorSnapshot sp500Snapshot = execute(indicatorContext, IndicatorType.SP500);
        IndicatorSnapshot nasdaqSnapshot = execute(indicatorContext, IndicatorType.NASDAQ);

        Double dxyCurrent = currentValue(dxySnapshot);
        Double dxyPrevious = previousValue(dxySnapshot, DxyIndicator.V_PREVIOUS);
        Double sp500Current = currentValue(sp500Snapshot);
        Double sp500Previous = previousValue(sp500Snapshot, Sp500Indicator.V_PREVIOUS);
        Double nasdaqCurrent = currentValue(nasdaqSnapshot);
        Double nasdaqPrevious = previousValue(nasdaqSnapshot, Sp500Indicator.V_PREVIOUS);

        if (dxyCurrent == null || dxyPrevious == null
                || sp500Current == null || sp500Previous == null
                || nasdaqCurrent == null || nasdaqPrevious == null) {
            logger.warn("{} : DXY/SP500/NASDAQ incomplet (valeur courante ou previous manquante), "
                    + "aucun OpinionEvent publié (dxy={}/{}, sp500={}/{}, nasdaq={}/{})",
                    getName(), dxyCurrent, dxyPrevious, sp500Current, sp500Previous, nasdaqCurrent, nasdaqPrevious);
            return;
        }

        double dxyChangePct = changePct(dxyCurrent, dxyPrevious);
        double sp500ChangePct = changePct(sp500Current, sp500Previous);
        double nasdaqChangePct = changePct(nasdaqCurrent, nasdaqPrevious);

        double score = computeRiskAppetiteScore(
                dxyChangePct, sp500ChangePct, nasdaqChangePct,
                dxyScale, equityScale, dxyWeight, sp500Weight, nasdaqWeight);

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(score);

        // Fraîcheur (étude §2.3) : le facteur le plus conservateur des deux quotes actions retenu.
        // Étude "unification-confidence-modulator" : computeStalenessDampening n'est plus appelée
        // directement ici, mais via l'adaptateur StalenessModulator (une seule instance qui reproduit
        // le Math.min ci-dessous, cf. javadoc StalenessModulator §5 point 2) + la boucle commune
        // ConfidenceModulation.
        Instant now = context.clock().now();
        StalenessModulator stalenessModulator = new StalenessModulator(now, staleQuoteHours,
                new StalenessModulator.StalenessInput("SP500", lastTradeTime(sp500Snapshot)),
                new StalenessModulator.StalenessInput("NASDAQ", lastTradeTime(nasdaqSnapshot)));
        List<ModulationResult> modulationResults = ConfidenceModulation.evaluateAll(
                List.of(stalenessModulator), context, parameters);
        double stalenessFactor = ConfidenceModulation.combinedFactor(modulationResults);
        double confidence = confidenceSignal.confidence * stalenessFactor;

        logger.debug("{} : dxyChangePct={}, sp500ChangePct={}, nasdaqChangePct={} => score={}, signal={}, "
                        + "confidence(raw)={}, stalenessFactor={}, confidence(final)={}",
                getName(), dxyChangePct, sp500ChangePct, nasdaqChangePct, score, confidenceSignal.signal,
                confidenceSignal.confidence, stalenessFactor, confidence);

        // Opinion MACRO : pas de symbole par construction (signal d'ambiance, cf. javadoc classe),
        // même vérification défensive que GlobalMarketOpinion au cas où un symbole s'y glisserait.
        Optional<String> opinionSymbol = symbol != null ? Optional.of(symbol) : Optional.empty();

        OpinionEvent event = new OpinionEvent(new OpinionSignal(
                getId(),
                opinionSymbol,
                confidenceSignal.signal,
                confidenceSignal.signal,
                confidence,
                score,
                getScope(),
                Set.of("DXY", "SP500", "NASDAQ"),
                String.format("DXY(%.4f) SP500(%.4f) NASDAQ(%.4f)", dxyChangePct, sp500ChangePct, nasdaqChangePct),
                now
        ));

        eventBus.publish(event);
    }

    /**
     * Score risk-on/risk-off [-1,1] (étude §2.2) : dollar fort = risk-off (poids négatif), SP500/
     * NASDAQ en hausse = risk-on (poids positifs). Logique pure, isolée pour être testable en
     * unitaire (patron {@code DxyIndicator.computeDxy}/{@code MovementQualificationStrategy.computeSignal}).
     */
    static double computeRiskAppetiteScore(
            double dxyChangePct, double sp500ChangePct, double nasdaqChangePct,
            double dxyScale, double equityScale,
            double dxyWeight, double sp500Weight, double nasdaqWeight) {
        double score = -dxyWeight * MarketOpinionHelper.normalizeChangeScore(dxyChangePct, dxyScale)
                + sp500Weight * MarketOpinionHelper.normalizeChangeScore(sp500ChangePct, equityScale)
                + nasdaqWeight * MarketOpinionHelper.normalizeChangeScore(nasdaqChangePct, equityScale);
        return Math.clamp(score, -1.0, 1.0);
    }

    private static double changePct(double current, double previous) {
        return previous == 0 ? 0.0 : (current - previous) / previous;
    }

    private IndicatorSnapshot execute(IndicatorContext indicatorContext, IndicatorType type) {
        ApiCredentialDTO credential = credentialResolver.resolve(type);
        IndicatorParameters params = new IndicatorParameters(type, Map.of(), Map.of(), Map.of(), credential);
        return indicatorEngine.execute(indicatorContext, params);
    }

    private static Double currentValue(IndicatorSnapshot snapshot) {
        IndicatorResult result = snapshot.getResult();
        return result.isValid() ? result.getValue() : null;
    }

    private static Double previousValue(IndicatorSnapshot snapshot, String key) {
        IndicatorResult result = snapshot.getResult();
        if (!result.isValid() || result.getValues() == null) {
            return null;
        }
        return result.getValues().get(key);
    }

    private static Long lastTradeTime(IndicatorSnapshot snapshot) {
        IndicatorResult result = snapshot.getResult();
        if (!result.isValid() || result.getValues() == null) {
            return null;
        }
        Double raw = result.getValues().get(Sp500Indicator.V_LAST_TRADE_TIME);
        return raw != null ? raw.longValue() : null;
    }

    private static double get(MarketOpinionParameters parameters, String key, double defaultValue) {
        return parameters != null ? parameters.get(key, defaultValue) : defaultValue;
    }

    private TimeFrame resolveTimeFrame(MarketOpinionParameters parameters) {
        String tf = parameters != null
                ? parameters.get(P_TIME_FRAME_NAME, DEFAULT_TIME_FRAME.toString())
                : DEFAULT_TIME_FRAME.toString();
        return TimeFrame.valueOf(tf);
    }
}
