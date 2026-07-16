package fr.ses10doigts.tradeIO5.service.tree.api.mcp;

import fr.ses10doigts.tradeIO5.exceptions.TreeAnalysisException;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.tree.api.mcp.dto.IndicatorSpec;
import fr.ses10doigts.tradeIO5.service.tree.api.mcp.dto.StrategySpec;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools MCP exposant la chaîne Indicator → Strategy → Opinion à un appelant LLM.
 * <p>
 * Chaque tool ne fait qu'une chose : traduire une entrée simple/JSON-friendly (enums en
 * String, params en Map) vers les types de domaine attendus par {@link TreeAnalysisFacade},
 * puis sérialiser la réponse dans une Map lisible (pas le {@code toString()} des objets
 * Java). L'orchestration réelle (candles → indicateurs → stratégie → opinion) est déléguée
 * à {@link TreeAnalysisFacade}.
 * <p>
 * Les méthodes @Tool renvoient une {@code String} JSON (et non la {@code Map} directement) :
 * avec spring-ai-starter-mcp-server-webmvc 1.0.9, un retour {@code Map<String,Object>} est
 * poussé dans {@code structuredContent} sans remplir correctement le tableau {@code content}
 * attendu par les clients MCP stricts (content[0] ne matche alors aucun schéma text/image/
 * audio/resource), ce qui fait échouer la validation côté client. Sérialiser nous-mêmes en
 * String garantit un {@code content: [{type: "text", text: ...}]} conforme.
 */
@Component
public class TreeAnalysisMcpTools {

    private static final Logger logger = LoggerFactory.getLogger(TreeAnalysisMcpTools.class);

    private final TreeAnalysisFacade treeAnalysisFacade;
    private final StrategyRegistry strategyRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TreeAnalysisMcpTools(TreeAnalysisFacade treeAnalysisFacade, StrategyRegistry strategyRegistry) {
        this.treeAnalysisFacade = treeAnalysisFacade;
        this.strategyRegistry = strategyRegistry;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize MCP tool result", e);
        }
    }

    /**
     * spring-ai-starter-mcp-server-webmvc 1.0.9 avale les exceptions levées par un tool sans
     * jamais logger la stacktrace, et construit un CallToolResult d'erreur dont le content[0]
     * ({@code {"type":"text"}}) n'a pas de champ "text" — ce qui fait échouer la validation
     * côté client MCP. On attrape donc nous-mêmes ici : on logge la vraie exception, et on
     * renvoie un JSON normal (non-erreur) décrivant l'échec, pour rester sur le chemin
     * "content" qui fonctionne correctement.
     */
    private String toJsonOrError(String toolName, java.util.function.Supplier<Map<String, Object>> supplier) {
        try {
            return toJson(supplier.get());
        } catch (Exception e) {
            logger.error("❌ Tool MCP '{}' a échoué", toolName, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", true);
            error.put("tool", toolName);
            error.put("exception", e.getClass().getName());
            error.put("message", e.getMessage());
            java.util.List<String> frames = new ArrayList<>();
            for (StackTraceElement el : e.getStackTrace()) {
                frames.add(el.toString());
                if (frames.size() >= 15) break;
            }
            error.put("stackTop", frames);
            if (e.getCause() != null) {
                error.put("causeType", e.getCause().getClass().getName());
                error.put("causeMessage", e.getCause().getMessage());
            }
            return toJson(error);
        }
    }

    @Tool(
            name = "get_indicator",
            description = "Calcule un indicateur pour un symbole et une timeframe donnés, en récupérant les candles "
                    + "réelles nécessaires. Couvre 3 familles : (1) indicateurs techniques internes calculés sur les "
                    + "candles — SMA, EMA, RSI, MACD, RAINBOW, ADX, ATR, BOLLINGER, OBV, REJECTION_ZONE ; (2) "
                    + "indicateurs externes nécessitant une credential fournisseur déjà configurée côté serveur — "
                    + "FEAR_GREED (CoinStats), STABLECOIN_MARKET_CAP (DefiLlama), OPEN_INTEREST/FUNDING_RATE/"
                    + "LIQUIDATIONS (Coinalyze, par symbole), DXY (Twelve Data), SP500/NASDAQ (Yahoo Finance), "
                    + "ETF_FLOW (SoSoValue, paramètre stringParams['asset']='BTC'|'ETH', 'BTC' par défaut) ; "
                    + "(3) ORDER_BOOK (carnet d'ordres Binance, sans credential). Retourne la valeur calculée, ses "
                    + "bornes théoriques et si le calcul est valide (ex: credential manquante ou historique trop court)."
    )
    public String getIndicator(
            @ToolParam(description = "Symbole du marché, ex: BTCUSDT") String symbol,
            @ToolParam(description = "Timeframe des candles: Y1, Y3, M1, M2, M3, M6, W1, W2, D1, H1, H4, H12, MIN1, MIN5") TimeFrame timeFrame,
            @ToolParam(description = "Type d'indicateur: SMA, EMA, RSI, MACD, FEAR_GREED, RAINBOW, ADX, ATR, BOLLINGER, "
                    + "OBV, STABLECOIN_MARKET_CAP, OPEN_INTEREST, FUNDING_RATE, LIQUIDATIONS, ORDER_BOOK, DXY, SP500, "
                    + "NASDAQ, ETF_FLOW, REJECTION_ZONE") IndicatorType type,
            @ToolParam(description = "Paramètres numériques de l'indicateur (ex: {\"period\": 14})", required = false) Map<String, Double> numericParams,
            @ToolParam(description = "Paramètres texte de l'indicateur (ex: {\"asset\": \"ETH\"} pour ETF_FLOW)", required = false) Map<String, String> stringParams
    ) {
        return toJsonOrError("get_indicator", () -> {
            IndicatorSnapshot snapshot = treeAnalysisFacade.getIndicator(
                    symbol, timeFrame, type, numericParams, stringParams != null ? stringParams : Map.of());
            return indicatorResponse(symbol, timeFrame, snapshot);
        });
    }

    @Tool(
            name = "evaluate_strategy",
            description = "Évalue une stratégie de trading pour un symbole donné : calcule les indicateurs requis à "
                    + "partir de candles réelles, puis exécute la logique de la stratégie. Stratégies disponibles : "
                    + "TrendConfirmationStrategy (ENTRY, EMA rapide + EMA lente + ADX + RSI), "
                    + "MovementQualificationStrategy (CONFIDENCE_MODULATOR, requiert exactement 1 OPEN_INTEREST + "
                    + "1 FUNDING_RATE + 1 OBV en 'indicators' — nécessite la credential Coinalyze) et "
                    + "OrderFlowStrategy (CONFIDENCE_MODULATOR, requiert exactement 1 ORDER_BOOK + 1 LIQUIDATIONS en "
                    + "'indicators' — nécessite la credential Coinalyze pour LIQUIDATIONS). Les Strategies "
                    + "CONFIDENCE_MODULATOR ne votent pas sur la direction du marché : leur score qualifie la "
                    + "fiabilité d'un mouvement (cascade de liquidations, sur-effet-de-levier, épuisement...), à "
                    + "utiliser comme atténuateur de confidence dans une Opinion plutôt que comme signal isolé. "
                    + "La résolution entre plusieurs stratégies du même StrategyType se fait selon les indicateurs "
                    + "fournis dans 'indicators'. Retourne un score (-1 à 1), une confiance, et le type de signal."
    )
    public String evaluateStrategy(
            @ToolParam(description = "Symbole du marché, ex: BTCUSDT") String symbol,
            @ToolParam(description = "Timeframe de référence pour cette évaluation") TimeFrame timeFrame,
            @ToolParam(description = "Type de stratégie: ENTRY, EXIT, RISK, CONFIDENCE_MODULATOR") StrategyType strategyType,
            @ToolParam(description = "Indicateurs requis par la stratégie (type, timeframe, paramètres numériques)") List<IndicatorSpec> indicators,
            @ToolParam(description = "Paramètres numériques propres à la stratégie", required = false) Map<String, Double> numericParams,
            @ToolParam(description = "Paramètres texte propres à la stratégie", required = false) Map<String, String> stringParams,
            @ToolParam(description = "Paramètres booléens propres à la stratégie", required = false) Map<String, Boolean> booleanParams
    ) {
        return toJsonOrError("evaluate_strategy", () -> {
            StrategySpec spec = new StrategySpec(strategyType, indicators, numericParams, stringParams, booleanParams);
            StrategyParameters params = toStrategyParameters(spec);

            StrategySignal signal = treeAnalysisFacade.evaluateStrategy(symbol, timeFrame, strategyType, params);
            return strategyResponse(symbol, timeFrame, signal);
        });
    }

    @Tool(
            name = "get_opinion",
            description = "Produit une opinion de marché pour un symbole donné. Pour le scope LOCAL (ex: "
                    + "DefaultMarketOpinion), évalue une ou plusieurs stratégies à partir de candles réelles, "
                    + "puis pondère leurs signaux : renseigner 'strategies'. Pour GLOBAL/MACRO (sentiment Fear & "
                    + "Greed) et EXTERNAL (avis LLM), 'strategies' est ignoré et peut être une liste vide : ces "
                    + "opinions ne dépendent d'aucune Strategy. Retourne le signal pondéré, la confiance, le "
                    + "score et les sources ayant contribué."
    )
    public String getOpinion(
            @ToolParam(description = "Symbole du marché, ex: BTCUSDT") String symbol,
            @ToolParam(description = "Périmètre de l'opinion: LOCAL, GLOBAL, MACRO, EXTERNAL") OpinionScope scope,
            @ToolParam(description = "Stratégies à évaluer (scope LOCAL uniquement ; ignoré pour GLOBAL/MACRO/EXTERNAL, peut être vide dans ce cas)") List<StrategySpec> strategies
    ) {
        return toJsonOrError("get_opinion", () -> {
            MarketOpinionParameters params = toMarketOpinionParameters(strategies);
            OpinionSignal signal = treeAnalysisFacade.getOpinion(symbol, scope, params);
            return opinionResponse(signal);
        });
    }

    // =====================================================================================
    // Conversion : entrées MCP simples -> types de domaine
    // =====================================================================================

    private StrategyParameters toStrategyParameters(StrategySpec spec) {
        StrategyParameters params = new StrategyParameters();
        params.setNumericParams(spec.numericParams() != null ? spec.numericParams() : Map.of());
        params.setStringParams(spec.stringParams() != null ? spec.stringParams() : Map.of());
        params.setBooleanParams(spec.booleanParams() != null ? spec.booleanParams() : Map.of());
        params.setIndicatorParameters(toIndicatorParameters(spec.indicators()));
        return params;
    }

    private Map<IndicatorKey, IndicatorParameters> toIndicatorParameters(List<IndicatorSpec> indicatorSpecs) {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = new HashMap<>();
        if (indicatorSpecs == null) {
            return indicatorParameters;
        }
        for (IndicatorSpec spec : indicatorSpecs) {
            if (spec.indicatorType() == null || spec.timeFrame() == null) {
                throw new TreeAnalysisException("Each indicator spec requires an indicatorType and a timeFrame");
            }
            // Convention partagée par toutes les Strategy (cf. TrendConfirmationStrategy) pour
            // savoir sur quel TimeFrame lire l'indicateur au sein du MarketContext.
            Map<String, String> strings = new HashMap<>();
            strings.put(TrendConfirmationStrategy.P_TIME_FRAME_NAME, spec.timeFrame().toString());

            IndicatorParameters indicatorParams = new IndicatorParameters(
                    spec.indicatorType(),
                    spec.numericParams() != null ? spec.numericParams() : Map.of(),
                    strings,
                    Map.of(),
                    // Nécessaire pour les indicateurs externes (ex: FEAR_GREED) : sans credential
                    // résolue ici, une Strategy consommant FEAR_GREED via evaluate_strategy/
                    // get_opinion recevrait un IndicatorResult invalid, alors que get_indicator
                    // (qui appelle déjà resolveCredential) fonctionne correctement pour le même
                    // indicateur.
                    treeAnalysisFacade.resolveCredential(spec.indicatorType())
            );

            IndicatorKey key = new IndicatorKey(spec.indicatorType(), spec.timeFrame(), indicatorParams);
            indicatorParameters.put(key, indicatorParams);
        }
        return indicatorParameters;
    }

    private MarketOpinionParameters toMarketOpinionParameters(List<StrategySpec> strategySpecs) {
        List<StrategyKey> keys = new ArrayList<>();
        if (strategySpecs != null) {
            for (StrategySpec spec : strategySpecs) {
                if (spec.strategyType() == null) {
                    throw new TreeAnalysisException("Each strategy spec requires a strategyType");
                }
                StrategyParameters strategyParameters = toStrategyParameters(spec);
                // Résolution désormais déléguée à StrategyRegistry#resolveBestMatch, qui
                // désambiguïse par Strategy#accepts(...) plutôt que par matches.get(0) (bug
                // découvert en testant l'opinion GLOBAL/FearGreedStrategy via ce tool : ENTRY
                // résolvait systématiquement vers TrendConfirmationStrategy).
                Strategy strategy;
                try {
                    strategy = strategyRegistry.resolveBestMatch(spec.strategyType(), strategyParameters);
                } catch (IllegalArgumentException e) {
                    throw new TreeAnalysisException(e.getMessage(), e);
                }
                keys.add(new StrategyKey(strategy, strategyParameters));
            }
        }

        return MarketOpinionParameters.builder()
                .strategies(keys)
                .build();
    }

    // =====================================================================================
    // Conversion : résultats de domaine -> JSON simple et lisible
    // =====================================================================================

    private static Map<String, Object> indicatorResponse(String symbol, TimeFrame timeFrame, IndicatorSnapshot snapshot) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", symbol);
        response.put("timeFrame", timeFrame.name());
        response.put("indicatorType", snapshot.getIndicatorType().name());
        response.put("valid", snapshot.getResult().isValid());
        response.put("value", snapshot.getResult().getValue());
        response.put("min", snapshot.getResult().getMin());
        response.put("max", snapshot.getResult().getMax());
        response.put("values", snapshot.getResult().getValues());
        return response;
    }

    private static Map<String, Object> strategyResponse(String symbol, TimeFrame timeFrame, StrategySignal signal) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", symbol);
        response.put("timeFrame", timeFrame.name());
        response.put("strategyName", signal.getStrategyName());
        response.put("valid", signal.isValid());
        response.put("signal", signal.getType() != null ? signal.getType().name() : null);
        response.put("score", signal.getScore());
        response.put("confidence", signal.getConfidence());
        response.put("reason", signal.getReason());
        response.put("metadata", signal.getMetadata());
        return response;
    }

    private static Map<String, Object> opinionResponse(OpinionSignal signal) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("opinionId", signal.opinionId());
        response.put("symbol", signal.symbol().isPresent() ? signal.symbol().orElse(null) : null);
        response.put("scope", signal.scope() != null ? signal.scope().name() : null);
        response.put("majoritySignal", signal.majoritySignal() != null ? signal.majoritySignal().name() : null);
        response.put("weightedSignal", signal.weightedSignal() != null ? signal.weightedSignal().name() : null);
        response.put("confidence", signal.confidence());
        response.put("score", signal.score());
        response.put("sources", signal.sources());
        response.put("reason", signal.reason());
        response.put("timestamp", signal.timestamp() != null ? signal.timestamp().toString() : null);
        return response;
    }
}
