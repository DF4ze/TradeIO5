package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
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
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.StablecoinMarketCapIndicator;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.ConfidenceModulation;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.ModulationResult;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.SentimentShiftModulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opinion {@code GLOBAL} (étude "extension-risk-macro-external" §3, scindée de {@code MACRO} par
 * l'étude "nouvelles-opinions-indicateurs-non-branches" §1) : sentiment du marché crypto lui-même
 * (Fear &amp; Greed + capitalisation stablecoin), sans lien avec un symbole précis. La toile de
 * fond TradFi (DXY/SP500/NASDAQ) vit désormais séparément dans {@code MacroMarketOpinion} (scope
 * {@code MACRO}) — voir cette étude §1 pour le raisonnement de la répartition.
 * <p>
 * Implémente {@link MarketOpinion} directement plutôt que d'étendre {@code AbstractMarketOpinion}
 * (comme {@link ExternalMarketOpinion}), et lit les indicateurs FEAR_GREED/STABLECOIN_MARKET_CAP
 * directement via {@link IndicatorEngine} : ni l'un ni l'autre n'est une décision d'entrée/sortie
 * sur un actif (ce que modélise {@code Strategy}), mais une simple lecture de sentiment/liquidité
 * globale. Les faire passer par {@code Strategy}/{@code StrategyAggregator} n'apportait donc rien
 * et forçait un rattachement arbitraire à {@code StrategyType.ENTRY} (l'ancienne
 * {@code FearGreedStrategy}, désormais repliée ici).
 * <p>
 * Lecture contrarian pour Fear &amp; Greed : peur extrême (valeur basse) traitée comme un signal
 * haussier (rebond probable), avidité extrême (valeur haute) comme un signal baissier.
 * <p>
 * STABLECOIN_MARKET_CAP (étude "nouvelles-opinions-indicateurs-non-branches" §3) : proxy de
 * liquidité crypto-native (capital déjà entré dans l'écosystème). Sa croissance hebdomadaire est
 * combinée au score Fear &amp; Greed avec un poids minoritaire ({@link #P_STABLECOIN_WEIGHT}, défaut
 * {@link #DEFAULT_STABLECOIN_WEIGHT}) : Fear &amp; Greed reste le signal dominant (déjà en place,
 * dampening déjà affiné), le stablecoin score est neuf et doit faire ses preuves. Si l'indicateur
 * est invalide/indisponible, repli propre sur 100% Fear &amp; Greed (jamais d'invalidation de toute
 * l'Opinion pour un signal secondaire manquant) — le dampening
 * {@link MarketOpinionHelper#computeSentimentShiftDampening} continue de ne s'appliquer qu'à la
 * composante Fear &amp; Greed (pas de raison empirique d'étendre ce comportement au stablecoin score).
 */
@Component
public class GlobalMarketOpinion implements MarketOpinion {
    private static final Logger logger = LoggerFactory.getLogger(GlobalMarketOpinion.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";
    public static final String P_BUY_THRESHOLD = "fearGreedBuyThreshold";
    public static final String P_SELL_THRESHOLD = "fearGreedSellThreshold";
    public static final String P_DELTA_THRESHOLD = "fearGreedDeltaThreshold";
    public static final String P_STABLECOIN_WEEKLY_SCALE = "stablecoinWeeklyScale";
    public static final String P_STABLECOIN_WEIGHT = "stablecoinWeight";

    private static final double DEFAULT_BUY_THRESHOLD = 25.0;   // <= 25 : extreme fear -> contrarian BUY
    private static final double DEFAULT_SELL_THRESHOLD = 75.0;  // >= 75 : extreme greed -> contrarian SELL
    // Amplitude (points, sur 24h/"yesterday") au-delà de laquelle un Fear&Greed déjà en zone
    // extrême est jugé "a bougé trop vite" et voit sa confidence atténuée (étude §2) : valeur
    // par défaut proposée, pas mesurée empiriquement, à ajuster si le signal s'avère trop/trop peu
    // conservateur en pratique.
    private static final double DEFAULT_DELTA_THRESHOLD = 15.0;
    // Croissance hebdomadaire stablecoin "typique" au-delà de laquelle le score sature à ±1 (étude
    // "nouvelles-opinions-indicateurs-non-branches" §3) : valeur par défaut proposée, à calibrer.
    private static final double DEFAULT_STABLECOIN_WEEKLY_SCALE = 0.03;
    // Poids minoritaire : Fear&Greed reste dominant (1 - ce poids), le stablecoin score est neuf.
    private static final double DEFAULT_STABLECOIN_WEIGHT = 0.4;
    private static final TimeFrame DEFAULT_TIME_FRAME = TimeFrame.H1;

    private final IndicatorEngine indicatorEngine;
    private final IndicatorCredentialResolver credentialResolver;

    @Autowired
    private EventBus eventBus;

    public GlobalMarketOpinion(IndicatorEngine indicatorEngine, IndicatorCredentialResolver credentialResolver) {
        this.indicatorEngine = indicatorEngine;
        this.credentialResolver = credentialResolver;
    }

    @Override
    public OpinionScope getScope() {
        return OpinionScope.GLOBAL;
    }

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(MarketOpinionParameters parameters) {
        // FEAR_GREED ne lit jamais context.marketDataset()/context.symbol() (valeur externe
        // unique) : aucune bougie à pré-charger.
        return Map.of();
    }

    @Override
    public void decide(OpinionContext context, MarketOpinionParameters parameters) {
        TimeFrame tf = resolveTimeFrame(parameters);
        double buyThreshold = parameters != null
                ? parameters.get(P_BUY_THRESHOLD, DEFAULT_BUY_THRESHOLD) : DEFAULT_BUY_THRESHOLD;
        double sellThreshold = parameters != null
                ? parameters.get(P_SELL_THRESHOLD, DEFAULT_SELL_THRESHOLD) : DEFAULT_SELL_THRESHOLD;
        double deltaThreshold = parameters != null
                ? parameters.get(P_DELTA_THRESHOLD, DEFAULT_DELTA_THRESHOLD) : DEFAULT_DELTA_THRESHOLD;

        ApiCredentialDTO credential = credentialResolver.resolve(IndicatorType.FEAR_GREED);
        IndicatorParameters fgParams = new IndicatorParameters(
                IndicatorType.FEAR_GREED,
                Map.of(),
                Map.of(),
                Map.of(),
                credential
        );

        String symbol = context.marketContext() != null ? context.marketContext().symbol() : null;
        IndicatorContext indicatorContext = new IndicatorContext(symbol, tf, null, Map.of(), context.clock());

        IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, fgParams);

        if (!snapshot.getResult().isValid() || snapshot.getResult().getValues() == null) {
            logger.warn("{} : FEAR_GREED snapshot invalide, aucun OpinionEvent publié", getName());
            return;
        }

        Double now = snapshot.getResult().getValues().get("now");
        if (now == null) {
            logger.warn("{} : FEAR_GREED snapshot sans valeur 'now', aucun OpinionEvent publié", getName());
            return;
        }
        // "yesterday" (voire "lastWeek") est déjà présent dans la même réponse externe que "now"
        // (FearAndGreedResponse) : pas de nouvel appel réseau, juste une lecture supplémentaire du
        // même IndicatorResult.getValues() déjà obtenu ci-dessus.
        Double yesterday = snapshot.getResult().getValues().get("yesterday");

        // Réutilise computeRsiScore : malgré son nom, le mapping (valeur [0,100] + seuils
        // buy/sell -> score [-1,1]) n'a rien de spécifique au RSI, et c'est exactement le
        // mapping contrarian recherché ici.
        double fearGreedScore = MarketOpinionHelper.computeRsiScore(now, buyThreshold, sellThreshold);

        // STABLECOIN_MARKET_CAP (étude "nouvelles-opinions-indicateurs-non-branches" §3) : proxy de
        // liquidité crypto-native, combiné en complément de Fear&Greed avec un poids minoritaire.
        // Repli propre sur 100% Fear&Greed si l'indicateur est invalide/indisponible (jamais
        // d'invalidation de toute l'Opinion pour un signal secondaire manquant).
        double stablecoinWeeklyScale = parameters != null
                ? parameters.get(P_STABLECOIN_WEEKLY_SCALE, DEFAULT_STABLECOIN_WEEKLY_SCALE) : DEFAULT_STABLECOIN_WEEKLY_SCALE;
        double stablecoinWeight = parameters != null
                ? parameters.get(P_STABLECOIN_WEIGHT, DEFAULT_STABLECOIN_WEIGHT) : DEFAULT_STABLECOIN_WEIGHT;

        ApiCredentialDTO stablecoinCredential = credentialResolver.resolve(IndicatorType.STABLECOIN_MARKET_CAP);
        IndicatorParameters stablecoinParams = new IndicatorParameters(
                IndicatorType.STABLECOIN_MARKET_CAP, Map.of(), Map.of(), Map.of(), stablecoinCredential);
        IndicatorSnapshot stablecoinSnapshot = indicatorEngine.execute(indicatorContext, stablecoinParams);

        Double stablecoinScore = null;
        if (stablecoinSnapshot.getResult().isValid() && stablecoinSnapshot.getResult().getValues() != null) {
            Double total = stablecoinSnapshot.getResult().getValues().get(StablecoinMarketCapIndicator.V_TOTAL);
            Double totalPrevWeek = stablecoinSnapshot.getResult().getValues().get(StablecoinMarketCapIndicator.V_TOTAL_PREV_WEEK);
            if (total != null) {
                stablecoinScore = MarketOpinionHelper.computeStablecoinScore(total, totalPrevWeek, stablecoinWeeklyScale);
            }
        }

        double score = stablecoinScore != null
                ? (1 - stablecoinWeight) * fearGreedScore + stablecoinWeight * stablecoinScore
                : fearGreedScore;

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(score);

        // Étude §2 : une hausse/baisse brutale du Fear&Greed en zone extrême est plutôt un signe de
        // retournement probable qu'un signal contrarian fiable à suivre tel quel -> on atténue la
        // confidence (jamais le score directionnel) proportionnellement à l'ampleur du mouvement.
        // Le dampening reste calculé sur Fear&Greed seul (now/yesterday), pas sur le score combiné :
        // pas de raison empirique d'étendre ce comportement au stablecoin score (cf. javadoc classe).
        // Étude "unification-confidence-modulator" : computeSentimentShiftDampening n'est plus
        // appelée directement ici, mais via l'adaptateur SentimentShiftModulator + la boucle commune
        // ConfidenceModulation (même calcul, même résultat).
        SentimentShiftModulator sentimentShiftModulator = new SentimentShiftModulator(
                now, yesterday, buyThreshold, sellThreshold, deltaThreshold);
        List<ModulationResult> modulationResults = ConfidenceModulation.evaluateAll(
                List.of(sentimentShiftModulator), context, parameters);
        double dampeningFactor = ConfidenceModulation.combinedFactor(modulationResults);
        double confidence = confidenceSignal.confidence * dampeningFactor;

        double delta = yesterday != null ? now - yesterday : 0.0;
        logger.debug("{} : Fear&Greed(now={}, yesterday={}, delta={}, score={}), stablecoinScore={} "
                        + "=> combinedScore={}, signal={}, confidence(raw)={}, dampeningFactor={}, confidence(final)={}",
                getName(), now, yesterday, delta, fearGreedScore, stablecoinScore, score, confidenceSignal.signal,
                confidenceSignal.confidence, dampeningFactor, confidence);

        // Opinion GLOBAL : pas de symbole par construction (contexte de marché large), mais on
        // garde la même vérification défensive que DefaultMarketOpinion au cas où un symbole
        // s'y glisserait malgré tout.
        Optional<String> opinionSymbol = symbol != null ? Optional.of(symbol) : Optional.empty();

        Set<String> sources = stablecoinScore != null
                ? Set.of("FEAR_GREED", "STABLECOIN_MARKET_CAP")
                : Set.of("FEAR_GREED");
        String reason = stablecoinScore != null
                ? "Fear&Greed(now=" + now + ", yesterday=" + yesterday + ", score=" + fearGreedScore
                        + ") + Stablecoin(score=" + stablecoinScore + ", weight=" + stablecoinWeight + ")"
                : "Fear&Greed(now=" + now + ", yesterday=" + yesterday + ")";

        OpinionEvent event = new OpinionEvent(new OpinionSignal(
                getId(),
                opinionSymbol,
                confidenceSignal.signal,
                confidenceSignal.signal,
                confidence,
                score,
                getScope(),
                sources,
                reason,
                context.clock().now()
        ));

        eventBus.publish(event);
    }

    private TimeFrame resolveTimeFrame(MarketOpinionParameters parameters) {
        String tf = parameters != null
                ? parameters.get(P_TIME_FRAME_NAME, DEFAULT_TIME_FRAME.toString())
                : DEFAULT_TIME_FRAME.toString();
        return TimeFrame.valueOf(tf);
    }
}
