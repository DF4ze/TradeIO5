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
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opinion {@code GLOBAL}/{@code MACRO} (étude "extension-risk-macro-external" §3) : sentiment
 * de marché large (Fear &amp; Greed), sans lien avec un symbole précis.
 * <p>
 * Implémente {@link MarketOpinion} directement plutôt que d'étendre {@code AbstractMarketOpinion}
 * (comme {@link ExternalMarketOpinion}), et lit l'indicateur FEAR_GREED directement via
 * {@link IndicatorEngine} : Fear &amp; Greed n'est pas une décision d'entrée/sortie sur un actif
 * (ce que modélise {@code Strategy}), mais une simple lecture de sentiment global, jamais agrégée
 * avec d'autres signaux. La faire passer par {@code Strategy}/{@code StrategyAggregator}
 * n'apportait donc rien et forçait un rattachement arbitraire à {@code StrategyType.ENTRY}
 * (l'ancienne {@code FearGreedStrategy}, désormais repliée ici).
 * <p>
 * Lecture contrarian : peur extrême (valeur basse) traitée comme un signal haussier (rebond
 * probable), avidité extrême (valeur haute) comme un signal baissier.
 * <p>
 * {@code GLOBAL} et {@code MACRO} restent fusionnés en un seul scope ({@code GLOBAL}) pour
 * l'instant, faute d'un deuxième indicateur macro-économique réel qui justifierait de les
 * séparer (voir étude, §3.3).
 */
@Component
public class GlobalMarketOpinion implements MarketOpinion {
    private static final Logger logger = LoggerFactory.getLogger(GlobalMarketOpinion.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";
    public static final String P_BUY_THRESHOLD = "fearGreedBuyThreshold";
    public static final String P_SELL_THRESHOLD = "fearGreedSellThreshold";

    private static final double DEFAULT_BUY_THRESHOLD = 25.0;   // <= 25 : extreme fear -> contrarian BUY
    private static final double DEFAULT_SELL_THRESHOLD = 75.0;  // >= 75 : extreme greed -> contrarian SELL
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

        // Réutilise computeRsiScore : malgré son nom, le mapping (valeur [0,100] + seuils
        // buy/sell -> score [-1,1]) n'a rien de spécifique au RSI, et c'est exactement le
        // mapping contrarian recherché ici.
        double score = MarketOpinionHelper.computeRsiScore(now, buyThreshold, sellThreshold);
        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(score);

        logger.debug("{} : Fear&Greed(now={}) => score={}, signal={}, confidence={}",
                getName(), now, score, confidenceSignal.signal, confidenceSignal.confidence);

        // Opinion GLOBAL : pas de symbole par construction (contexte de marché large), mais on
        // garde la même vérification défensive que DefaultMarketOpinion au cas où un symbole
        // s'y glisserait malgré tout.
        Optional<String> opinionSymbol = symbol != null ? Optional.of(symbol) : Optional.empty();

        OpinionEvent event = new OpinionEvent(new OpinionSignal(
                getId(),
                opinionSymbol,
                confidenceSignal.signal,
                confidenceSignal.signal,
                confidenceSignal.confidence,
                score,
                getScope(),
                Set.of("FEAR_GREED"),
                "Fear&Greed(now=" + now + ")",
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
