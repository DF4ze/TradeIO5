package fr.ses10doigts.tradeIO5.service.tree.api.mcp;

import fr.ses10doigts.tradeIO5.exceptions.TreeAnalysisException;
import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.UserProfile;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.WalletSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinionRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Façade d'orchestration Indicator → Strategy → Opinion.
 * <p>
 * Avant ce service, rien dans le code applicatif n'enchaînait réellement ces 3 couches en
 * dehors des tests (cf. {@code IndicatorEngineTest}, {@code TrendConfirmationStrategyTest},
 * {@code DefaultMarketOpinionTest_UT/_IT}). Cette classe reproduit la même séquence
 * d'appels que ces tests, pour un appelant "métier" (typiquement les tools MCP de
 * {@link TreeAnalysisMcpTools}) :
 * <ol>
 *     <li>Construction d'un {@link MarketDatasetRequest} et appel à
 *     {@link MarketDatasetEngine#getDataset(MarketDatasetRequest)} pour récupérer les candles.</li>
 *     <li>Construction du contexte (Indicator/Market/Opinion) adéquat.</li>
 *     <li>Appel de l'engine/registry correspondant (IndicatorEngine, StrategyRegistry,
 *     MarketOpinionRegistry).</li>
 * </ol>
 * <p>
 * Chaque méthode publique à 4 paramètres (celle utilisée par les tools MCP) utilise
 * {@link MarketDataSource#BINANCE} comme source réelle de marché, avec {@code endTime}
 * recalculé à {@link DomainClock#now()} à chaque appel (jamais une valeur figée). Les
 * surcharges avec {@link MarketDataSource} explicite existent pour permettre aux tests
 * d'utiliser {@link MarketDataSource#MEMORY} (comme le reste de la base de tests) sans
 * dépendre du réseau.
 * <p>
 * Le client Binance injecté est la version décorée par
 * {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.CachingMarketDataApiClient}
 * (bean {@code cachingBinanceMarketDataApiClient}, cf. {@link fr.ses10doigts.tradeIO5.configuration.MarketDataCachingConfig}) :
 * les candles closes déjà récupérées sont lues en base plutôt que re-fetchées au réseau, de
 * façon transparente pour cette façade (cf. docs/etude-cache-db-candles-h1.md).
 */
@Service
public class TreeAnalysisFacade {

    private static final Logger log = LoggerFactory.getLogger(TreeAnalysisFacade.class);

    private final MarketDatasetEngine marketDatasetEngine;
    private final IndicatorEngine indicatorEngine;
    private final IndicatorRegistry indicatorRegistry;
    private final StrategyRegistry strategyRegistry;
    private final MarketOpinionRegistry marketOpinionRegistry;
    private final EventBus eventBus;
    private final DomainClock clock;
    private final MarketDataApiClient binanceMarketDataApiClient;
    private final IndicatorCredentialResolver credentialResolver;

    public TreeAnalysisFacade(
            MarketDatasetEngine marketDatasetEngine,
            IndicatorEngine indicatorEngine,
            IndicatorRegistry indicatorRegistry,
            StrategyRegistry strategyRegistry,
            MarketOpinionRegistry marketOpinionRegistry,
            EventBus eventBus,
            DomainClock clock,
            @Qualifier("cachingBinanceMarketDataApiClient") MarketDataApiClient binanceMarketDataApiClient,
            IndicatorCredentialResolver credentialResolver
    ) {
        this.marketDatasetEngine = marketDatasetEngine;
        this.indicatorEngine = indicatorEngine;
        this.indicatorRegistry = indicatorRegistry;
        this.strategyRegistry = strategyRegistry;
        this.marketOpinionRegistry = marketOpinionRegistry;
        this.eventBus = eventBus;
        this.clock = clock;
        this.binanceMarketDataApiClient = binanceMarketDataApiClient;
        this.credentialResolver = credentialResolver;
    }

    // =====================================================================================
    // a. Indicator
    // =====================================================================================

    /** Point d'entrée réel (utilisé par le tool MCP {@code get_indicator}) : source = Binance. */
    public IndicatorSnapshot getIndicator(
            String symbol, TimeFrame timeFrame, IndicatorType type, Map<String, Double> numericParams
    ) {
        return getIndicator(symbol, timeFrame, type, numericParams, Map.of());
    }

    /**
     * Surcharge avec paramètres texte (ex: {@code "asset"="ETH"} pour {@code ETF_FLOW}) — cf.
     * docs/etude-sourcing-etf-flow-alternative-farside.md, point signalé par Clem le 2026-07-16 :
     * sans cette surcharge, {@code get_indicator} ne pouvait transmettre aucun paramètre non
     * numérique, donc {@code ETF_FLOW} retombait toujours sur le défaut de l'indicateur (BTC,
     * cf. {@code EtfFlowAsset.fromParameter}) quel que soit le {@code symbol} demandé (BTCUSDT vs
     * ETHUSDT n'influence jamais {@code EtfFlowIndicator}, qui lit exclusivement le paramètre
     * {@code "asset"} — pas le symbole).
     */
    public IndicatorSnapshot getIndicator(
            String symbol, TimeFrame timeFrame, IndicatorType type, Map<String, Double> numericParams,
            Map<String, String> stringParams
    ) {
        return getIndicator(symbol, timeFrame, type, numericParams, stringParams,
                MarketDataSource.BINANCE, binanceMarketDataApiClient);
    }

    /** Surcharge à source explicite (tests avec {@link MarketDataSource#MEMORY}, etc). */
    public IndicatorSnapshot getIndicator(
            String symbol, TimeFrame timeFrame, IndicatorType type, Map<String, Double> numericParams,
            MarketDataSource source, Object providerParam
    ) {
        return getIndicator(symbol, timeFrame, type, numericParams, Map.of(), source, providerParam);
    }

    /** Surcharge complète : source explicite + paramètres texte (cf. surcharge stringParams ci-dessus). */
    public IndicatorSnapshot getIndicator(
            String symbol, TimeFrame timeFrame, IndicatorType type, Map<String, Double> numericParams,
            Map<String, String> stringParams, MarketDataSource source, Object providerParam
    ) {
        requireSymbol(symbol);
        requireTimeFrame(timeFrame);
        Indicator indicator = resolveIndicator(type);

        IndicatorParameters parameters = new IndicatorParameters(
                type,
                numericParams != null ? numericParams : Map.of(),
                stringParams != null ? stringParams : Map.of(),
                Map.of(),
                resolveCredential(type)
        );

        // Indicator.getRequiredData(...) ne renvoie qu'un minimum indicatif (ex: la période RSI
        // exacte, sans marge : RsiIndicator.compute() a en réalité besoin de period + 1 points).
        // On demande systématiquement DEFAULT_LIMIT bougies plutôt que ce minimum exact, pour ne
        // jamais se retrouver avec une série juste sous le seuil de validité de l'indicateur.
        int lookBack = MarketDatasetEngine.DEFAULT_LIMIT;
        Instant now = clock.now();

        MarketDataset dataset = fetchDataset(symbol, timeFrame, lookBack, now, source, providerParam);

        IndicatorContext context = new IndicatorContext(symbol, timeFrame, dataset, Map.of(), clock);

        return indicatorEngine.execute(context, parameters);
    }

    // =====================================================================================
    // b. Strategy
    // =====================================================================================

    /** Point d'entrée réel (utilisé par le tool MCP {@code evaluate_strategy}) : source = Binance. */
    public StrategySignal evaluateStrategy(
            String symbol, TimeFrame timeFrame, StrategyType strategyType, StrategyParameters params
    ) {
        return evaluateStrategy(symbol, timeFrame, strategyType, params, MarketDataSource.BINANCE, binanceMarketDataApiClient);
    }

    /** Surcharge à source explicite (tests avec {@link MarketDataSource#MEMORY}, etc). */
    public StrategySignal evaluateStrategy(
            String symbol, TimeFrame timeFrame, StrategyType strategyType, StrategyParameters params,
            MarketDataSource source, Object providerParam
    ) {
        requireSymbol(symbol);
        requireTimeFrame(timeFrame);

        if (params == null) {
            throw new TreeAnalysisException("StrategyParameters must not be null");
        }

        Strategy strategy = resolveStrategy(strategyType, params);

        Instant now = clock.now();

        // TimeFrames requis par les indicateurs de la stratégie + le TF de référence demandé
        Map<TimeFrame, Integer> requiredCandles = new HashMap<>(strategy.getRequiredCandles(params));
        requiredCandles.merge(timeFrame, MarketDatasetEngine.DEFAULT_LIMIT, Math::max);

        MarketContext marketContext = buildMarketContext(symbol, requiredCandles, source, providerParam, now);

        return strategy.evaluate(marketContext, params);
    }

    // =====================================================================================
    // c. Opinion
    // =====================================================================================

    /** Point d'entrée réel (utilisé par le tool MCP {@code get_opinion}) : source = Binance. */
    public OpinionSignal getOpinion(String symbol, OpinionScope scope, MarketOpinionParameters params) {
        return getOpinion(symbol, scope, params, MarketDataSource.BINANCE, binanceMarketDataApiClient);
    }

    /** Surcharge à source explicite (tests avec {@link MarketDataSource#MEMORY}, etc). */
    public OpinionSignal getOpinion(
            String symbol, OpinionScope scope, MarketOpinionParameters params,
            MarketDataSource source, Object providerParam
    ) {
        requireSymbol(symbol);
        MarketOpinion opinion = resolveOpinion(scope);

        if (params == null) {
            throw new TreeAnalysisException("MarketOpinionParameters must not be null");
        }

        Instant now = clock.now();

        Map<TimeFrame, Integer> requiredCandles = opinion.getRequiredCandles(params);
        MarketContext marketContext = buildMarketContext(symbol, requiredCandles, source, providerParam, now);

        OpinionContext opinionContext = new OpinionContext(
                WalletSnapshot.builder().build(),
                UserProfile.builder().build(),
                marketContext,
                new HashMap<>(),
                clock
        );

        return decideAndCapture(opinion, opinionContext, params);
    }

    /**
     * S'abonne temporairement à {@link EventBus} pour capturer de façon synchrone
     * l'{@link OpinionEvent} publié par {@link MarketOpinion#decide}. {@link EventBus#publish}
     * étant synchrone (itère les subscribers dans le thread appelant), le consumer aura reçu
     * l'event avant que {@code decide(...)} ne retourne. Le désabonnement en {@code finally}
     * évite d'accumuler des consumers morts au fil des appels.
     */
    private OpinionSignal decideAndCapture(MarketOpinion opinion, OpinionContext context, MarketOpinionParameters params) {
        AtomicReference<OpinionSignal> captured = new AtomicReference<>();
        Consumer<OpinionEvent> listener = event -> captured.set(toOpinionSignal(event));

        eventBus.subscribe(OpinionEvent.class, listener);
        try {
            opinion.decide(context, params);
        } finally {
            eventBus.unsubscribe(OpinionEvent.class, listener);
        }

        OpinionSignal signal = captured.get();
        if (signal == null) {
            throw new TreeAnalysisException(
                    "MarketOpinion '" + opinion.getName() + "' did not emit any OpinionEvent (contract violation: decide() must emit an event)"
            );
        }
        return signal;
    }

    private static OpinionSignal toOpinionSignal(OpinionEvent event) {
        return new OpinionSignal(
                event.getOpinionId(),
                event.getSymbol(),
                event.getMajoritySignal(),
                event.getWeightedSignal(),
                event.getConfidence(),
                event.getScore(),
                event.getScope(),
                event.getSources(),
                event.getReason(),
                event.getTimestamp()
        );
    }

    // =====================================================================================
    // Helpers communs
    // =====================================================================================

    private MarketContext buildMarketContext(
            String symbol, Map<TimeFrame, Integer> requiredCandles, MarketDataSource source, Object providerParam, Instant now
    ) {
        Map<TimeFrame, Integer> timeFrames = requiredCandles.isEmpty()
                // Pas d'indicateur/dépendance déclarée : on récupère quand même une série de
                // référence pour ne pas construire un MarketContext totalement vide.
                ? Map.of(TimeFrame.H1, MarketDatasetEngine.DEFAULT_LIMIT)
                : requiredCandles;

        Map<TimeFrame, MarketDataset> series = new HashMap<>();
        for (TimeFrame timeFrame : timeFrames.keySet()) {
            // Indicator.getRequiredData(...) ne renvoie qu'un minimum indicatif (souvent la
            // période exacte, sans marge). On demande systématiquement DEFAULT_LIMIT bougies
            // par TimeFrame plutôt que ce minimum exact, pour ne jamais se retrouver avec une
            // série juste sous le seuil de validité de l'indicateur (ex: RSI invalide si
            // data.size() < period + 1).
            MarketDataset dataset = fetchDataset(symbol, timeFrame, MarketDatasetEngine.DEFAULT_LIMIT, now, source, providerParam);
            series.put(timeFrame, dataset);
        }

        BigDecimal lastPrice = extractLastPrice(series);

        return new MarketContext(symbol, lastPrice, clock, series, new HashMap<>());
    }

    private MarketDataset fetchDataset(
            String symbol, TimeFrame timeFrame, int lookBack, Instant now, MarketDataSource source, Object providerParam
    ) {
        MarketDatasetRequest request = new MarketDatasetRequest(symbol, timeFrame, lookBack, now, source, providerParam);
        try {
            return marketDatasetEngine.getDataset(request);
        } catch (IllegalStateException e) {
            // ex: MarketDataProviderRegistry sans factory pour la source demandée
            throw new TreeAnalysisException("Unable to load market data for " + symbol + " (" + source + "): " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new TreeAnalysisException("Invalid market dataset request for " + symbol + ": " + e.getMessage(), e);
        }
    }

    private static BigDecimal extractLastPrice(Map<TimeFrame, MarketDataset> series) {
        for (MarketDataset dataset : series.values()) {
            List<?> candles = dataset.getMarketDatas();
            if (candles != null && !candles.isEmpty()) {
                var last = dataset.getMarketDatas().get(candles.size() - 1);
                return last.getClose();
            }
        }
        return null;
    }

    /**
     * Résolution déléguée à {@link IndicatorCredentialResolver} (extrait de cette classe pour
     * être réutilisable par {@code GlobalMarketOpinion}, qui lit FEAR_GREED directement via
     * {@code IndicatorEngine} sans passer par cette façade). Rendu public : réutilisé par
     * {@code TreeAnalysisMcpTools#toIndicatorParameters} pour que les indicateurs nécessitant
     * une credential (ex: FEAR_GREED) fonctionnent aussi quand ils sont appelés via
     * {@code evaluate_strategy}/{@code get_opinion} (chemin générique Strategy/Opinion), pas
     * seulement via {@code get_indicator}.
     */
    public ApiCredentialDTO resolveCredential(IndicatorType type) {
        return credentialResolver.resolve(type);
    }

    private Indicator resolveIndicator(IndicatorType type) {
        if (type == null) {
            throw new TreeAnalysisException("IndicatorType must not be null");
        }
        if (!indicatorRegistry.contains(type)) {
            throw new TreeAnalysisException("Unknown indicator type: " + type);
        }
        return indicatorRegistry.get(type);
    }

    /**
     * Résolution désormais déléguée à {@link StrategyRegistry#resolveBestMatch}, qui
     * désambiguïse entre plusieurs Strategies partageant le même {@link StrategyType} via
     * {@code Strategy#accepts(StrategyParameters)} plutôt que de prendre le premier match.
     */
    private Strategy resolveStrategy(StrategyType strategyType, StrategyParameters params) {
        if (strategyType == null) {
            throw new TreeAnalysisException("StrategyType must not be null");
        }
        try {
            return strategyRegistry.resolveBestMatch(strategyType, params);
        } catch (IllegalArgumentException e) {
            throw new TreeAnalysisException(e.getMessage(), e);
        }
    }

    private MarketOpinion resolveOpinion(OpinionScope scope) {
        if (scope == null) {
            throw new TreeAnalysisException("OpinionScope must not be null");
        }
        List<MarketOpinion> matches = marketOpinionRegistry.get(scope);
        if (matches.isEmpty()) {
            throw new TreeAnalysisException("No MarketOpinion registered for scope: " + scope);
        }
        if (matches.size() > 1) {
            log.warn("Several opinions match scope {} ({}), using the first one: {}",
                    scope, matches, matches.get(0).getName());
        }
        return matches.get(0);
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new TreeAnalysisException("symbol must not be null or blank");
        }
    }

    private static void requireTimeFrame(TimeFrame timeFrame) {
        if (timeFrame == null) {
            throw new TreeAnalysisException("timeFrame must not be null");
        }
    }
}
