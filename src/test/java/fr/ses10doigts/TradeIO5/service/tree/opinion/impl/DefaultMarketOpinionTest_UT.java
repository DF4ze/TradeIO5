package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.UserProfile;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyAggregatorParam;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.RiskProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinionRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyAggregator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Cf. MarketDatasetEngineSpringTest / DoubleRsiStrategyTest : le MarketDatasetCache
// (singleton Spring) est indexé par flux natif (symbol + timeFrame + source +
// providerParam) et partagé entre toutes les classes de test utilisant le même contexte
// Spring ("fastTF"/H1/UPTREND est aussi utilisé ailleurs). On isole le contexte par méthode
// pour éviter toute pollution croisée.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Decision - Risk Management - UT")
@SpringBootTest
class DefaultMarketOpinionTest_UT {
    private static final Logger logger = LoggerFactory.getLogger(DefaultMarketOpinionTest_UT.class);

    @Autowired
    private StrategyRegistry strategyRegistry;
    @Autowired
    private MarketOpinionRegistry marketOpinionRegistry;
    @Autowired
    private MarketDatasetEngine marketDatasetEngine;
    @Autowired
    private EventBus eventBus;

    private static DomainClock clock;

    @BeforeAll
    static void init(){
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @BeforeEach
    void setUp() {
    }

  /*  @Test
    void testDecisionWithDifferentRiskProfiles() {
        // --- Préparation des StrategySignal simulés ---
        StrategySignal buyStrong = StrategySignal.simple(SignalType.BULLISH, 1.0, 1.0);
        StrategySignal sellWeak = StrategySignal.simple(SignalType.BEARISH, -0.4, 0.2);
        StrategySignal holdNeutral = StrategySignal.simple(SignalType.NEUTRAL, 0.2, 0.5);

        List<StrategySignal> sources = List.of(buyStrong, sellWeak, holdNeutral);

        // --- Paramètres génériques pour chaque profil ---
        OpinionParameters lowParams = OpinionParameters.builder()
                .riskProfile(RiskProfile.LOW)
                .build();

        OpinionParameters mediumParams = OpinionParameters.builder()
                .riskProfile(RiskProfile.MEDIUM)
                .build();

        OpinionParameters highParams = OpinionParameters.builder()
                .riskProfile(RiskProfile.HIGH)
                .build();

        // --- Contexte minimal pour la décision ---
        OpinionContext context = new OpinionContext(
                WalletSnapshot.builder().build(),
                UserProfile.builder().build(),
                new MarketContext(null, null, null, null, null),
                null,
                clock
        );

        // --- Instanciation de la décision avec une liste vide de strategies
        // car on va injecter directement les signaux ---
        RiskManagementMarketOpinion decision = new RiskManagementMarketOpinion();

        // --- Test profil LOW ---
        OpinionResult lowResult = decision.interpretSignals(sources, context, lowParams);
        System.out.println("LOW profile action: " + lowResult.getAction());
        assertEquals(MarketAction.HOLD, lowResult.getAction());

        // --- Test profil MEDIUM ---
        OpinionResult mediumResult = decision.interpretSignals(sources, context, mediumParams);
        System.out.println("MEDIUM profile action: " + mediumResult.getAction());
        assertEquals(MarketAction.HOLD, mediumResult.getAction());

        // --- Test profil HIGH ---
        OpinionResult highResult = decision.interpretSignals(sources, context, highParams);
        System.out.println("HIGH profile action: " + highResult.getAction());
        assertEquals(MarketAction.BUY, highResult.getAction());
    }

   */

    @Test
    void riskManagementFullChainTest(){
        // Build params
        Strategy strategy = strategyRegistry.get(DoubleRsiStrategy.class.getSimpleName());
        StrategyParametersFactory.RsiParam fastRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.H1, 12, 70, 30);
        StrategyParametersFactory.RsiParam slowRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.M1, 24, 60, 28);

        MarketOpinionParameters marketOpinionParameters = MarketOpinionParametersFactory.buildRiskManagementParamWithDoubleRSI(strategy, slowRsiParam, fastRsiParam, RiskProfile.MEDIUM);

        // Building dataset & MarketContext
        MarketDatasetRequest mdrSlow = new MarketDatasetRequest("slowTF", TimeFrame.H12, 50, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDatasetRequest mdrFast = new MarketDatasetRequest("fastTF", TimeFrame.H1, 50, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDataset slowDataset = marketDatasetEngine.getDataset(mdrSlow);
        MarketDataset fastDataset = marketDatasetEngine.getDataset(mdrFast);

        MarketContext marketContext = new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                clock,
                Map.of(
                        TimeFrame.M1, slowDataset,
                        TimeFrame.H1, fastDataset
                ),
                new HashMap<>()
        );

        OpinionContext opinionContext =  new OpinionContext(
                null,
                UserProfile.builder().riskProfile(RiskProfile.MEDIUM).build(),
                marketContext,
                new HashMap<>(),
                clock
        );

        //
        MarketOpinion marketOpinion = marketOpinionRegistry.get(DefaultMarketOpinion.class.getSimpleName());
        marketOpinion.decide(opinionContext, marketOpinionParameters);

       // assertSame(SignalType.BEARISH, decide.majoritySignal()); // TODO CheckEvent


    }

    /**
     * Chaîne complète Indicator -&gt; Strategy -&gt; Opinion pour {@link TrendConfirmationStrategy}
     * (EMA + ADX + RSI) branchée dans {@link DefaultMarketOpinion} (scope {@code LOCAL}) via
     * {@link StrategyAggregator}. Contrairement à {@link #riskManagementFullChainTest()}, on
     * s'abonne réellement à l'{@link OpinionEvent} publié par l'{@link EventBus} pour vérifier
     * son contenu plutôt que de se contenter de constater que {@code decide()} ne plante pas.
     */
    @Test
    @DisplayName("Chaîne complète TrendConfirmation -> Opinion LOCAL : tendance haussière confirmée -> BULLISH")
    void trendConfirmationFullChainTest_uptrend() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        // Seuil de surachat RSI volontairement inatteignable (100) pour isoler ici le
        // comportement EMA+ADX, comme dans TrendConfirmationStrategyTest#should_emit_BULLISH_on_confirmed_uptrend.
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TimeFrame.H1, 10, 20, 14, 14,
                15.0, 25.0,
                100.0, 20.0
        );
        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        OpinionEvent event = decideAndCapture(
                "trendOpinionUp", TimeFrame.H1, TrendType.UPTREND, marketOpinionParameters);

        assertNotNull(event, "OpinionEvent should have been published");
        assertEquals(OpinionScope.LOCAL, event.getScope());
        assertEquals(SignalType.BULLISH, event.getWeightedSignal());
        assertTrue(event.getScore() > 0);
    }

    @Test
    @DisplayName("Chaîne complète TrendConfirmation -> Opinion LOCAL : tendance baissière confirmée -> BEARISH")
    void trendConfirmationFullChainTest_downtrend() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        // Symétriquement, seuil de survente RSI inatteignable (0).
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TimeFrame.H1, 10, 20, 14, 14,
                15.0, 25.0,
                80.0, 0.0
        );
        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        OpinionEvent event = decideAndCapture(
                "trendOpinionDown", TimeFrame.H1, TrendType.DOWNTREND, marketOpinionParameters);

        assertNotNull(event, "OpinionEvent should have been published");
        assertEquals(OpinionScope.LOCAL, event.getScope());
        assertEquals(SignalType.BEARISH, event.getWeightedSignal());
        assertTrue(event.getScore() < 0);
    }

    @Test
    @DisplayName("Chaîne complète TrendConfirmation -> Opinion LOCAL : marché plat -> NEUTRAL (ADX bas neutralise malgré un éventuel bruit EMA/RSI)")
    void trendConfirmationFullChainTest_flat() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TimeFrame.H1, 10, 20, 14, 14,
                15.0, 25.0,
                80.0, 20.0
        );
        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        OpinionEvent event = decideAndCapture(
                "trendOpinionFlat", TimeFrame.H1, TrendType.FLAT, marketOpinionParameters);

        assertNotNull(event, "OpinionEvent should have been published");
        assertEquals(OpinionScope.LOCAL, event.getScope());
        assertEquals(SignalType.NEUTRAL, event.getWeightedSignal());
    }

    /**
     * Combine {@link TrendConfirmationStrategy} et {@link DoubleRsiStrategy} (Strategies
     * hétérogènes) dans la même {@link MarketOpinionParameters#getStrategies()} pour vérifier
     * concrètement, sur un cas réel à plusieurs Strategies, que la correction du bug de conflit
     * dans {@link StrategyAggregator} fonctionne : sur une tendance haussière continue, le RSI
     * atteint 100 (aucune perte), ce qui pousse DoubleRsiStrategy en SELL fort (RSI en surachat)
     * pendant que TrendConfirmationStrategy (EMA cross haussier + ADX fort) reste en BUY fort ->
     * les deux Strategies ne sont pas d'accord, et StrategyAggregator doit détecter le conflit.
     */
    @Test
    @DisplayName("Combinaison TrendConfirmation + DoubleRsi hétérogènes : conflit BUY vs SELL détecté par StrategyAggregator")
    void trendConfirmationAndDoubleRsiConflictTest() {
        TimeFrame fastTF = TimeFrame.H1;
        TimeFrame slowTF = TimeFrame.D1;

        Strategy trendStrategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        Strategy doubleRsiStrategy = strategyRegistry.get(DoubleRsiStrategy.class.getSimpleName());

        // TrendConfirmation : seuils RSI inatteignables pour isoler un BUY fort (EMA+ADX).
        StrategyParametersFactory.TrendConfirmationParam trendParam = new StrategyParametersFactory.TrendConfirmationParam(
                fastTF, 10, 20, 14, 14,
                15.0, 25.0,
                100.0, 20.0
        );
        StrategyParameters trendParameters = StrategyParametersFactory.buildTrendConfirmationStrategyParam(trendParam);

        // DoubleRsi : mêmes seuils que DoubleRsiStrategyTest#should_emit_SELL_when_rsi_is_overbuy,
        // qui produit un SELL fort sur cette même tendance haussière (RSI=100 en zone de surachat).
        StrategyParametersFactory.RsiParam slowRsiParam = new StrategyParametersFactory.RsiParam(slowTF, 28.0, 69.0, 31.0);
        StrategyParametersFactory.RsiParam fastRsiParam = new StrategyParametersFactory.RsiParam(fastTF, 14.0, 71.0, 29.0);
        StrategyParameters doubleRsiParameters = StrategyParametersFactory.buildDoubleRsiStrategyParam(slowRsiParam, fastRsiParam);

        MarketDatasetRequest mdrFast = new MarketDatasetRequest("trendConflictH1", fastTF, 60, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDatasetRequest mdrSlow = new MarketDatasetRequest("trendConflictD1", slowTF, 60, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);
        MarketDataset fastDataset = marketDatasetEngine.getDataset(mdrFast);
        MarketDataset slowDataset = marketDatasetEngine.getDataset(mdrSlow);

        MarketContext marketContext = new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                clock,
                Map.of(fastTF, fastDataset, slowTF, slowDataset),
                new HashMap<>()
        );

        StrategyKey trendKey = new StrategyKey(trendStrategy, trendParameters);
        StrategyKey doubleRsiKey = new StrategyKey(doubleRsiStrategy, doubleRsiParameters);

        MarketOpinionParameters marketOpinionParameters = MarketOpinionParameters.builder()
                .strategies(List.of(trendKey, doubleRsiKey))
                .build();

        OpinionContext opinionContext = new OpinionContext(
                null,
                UserProfile.builder().riskProfile(RiskProfile.MEDIUM).build(),
                marketContext,
                new HashMap<>(),
                clock
        );

        MarketOpinion marketOpinion = marketOpinionRegistry.get(DefaultMarketOpinion.class.getSimpleName());
        OpinionEvent event = captureOpinionEvent(marketOpinion, opinionContext, marketOpinionParameters);

        assertNotNull(event, "OpinionEvent should have been published");
        assertEquals(OpinionScope.LOCAL, event.getScope());

        // Vérification bas niveau directe du conflit détecté par StrategyAggregator (pas
        // seulement via le test unitaire ciblé de StrategyAggregator, mais sur ce cas réel à
        // plusieurs Strategies hétérogènes).
        StrategyAggregatorParam trendSap = new StrategyAggregatorParam(trendStrategy, trendParameters);
        StrategyAggregatorParam doubleRsiSap = new StrategyAggregatorParam(doubleRsiStrategy, doubleRsiParameters);
        AggregatedStrategySignal aggregatedSignal = StrategyAggregator.evaluate(marketContext, List.of(trendSap, doubleRsiSap));

        logger.debug("Conflict explanation: {}", aggregatedSignal.getExplanation());

        assertTrue(aggregatedSignal.isConflictDetected(),
                "Expected StrategyAggregator to detect a BUY vs SELL conflict between TrendConfirmation and DoubleRsi");
        assertNotNull(aggregatedSignal.getExplanation());
    }

    /**
     * Construit un dataset MEMORY mono-timeframe, l'appelle à travers {@code decide()}, et
     * capture réellement l'{@link OpinionEvent} publié (au lieu de se contenter de vérifier
     * l'absence d'exception).
     */
    private OpinionEvent decideAndCapture(
            String datasetSymbol,
            TimeFrame timeFrame,
            TrendType scenario,
            MarketOpinionParameters marketOpinionParameters
    ) {
        MarketDatasetRequest mdr = new MarketDatasetRequest(datasetSymbol, timeFrame, 60, Instant.now(), MarketDataSource.MEMORY, scenario);
        MarketDataset dataset = marketDatasetEngine.getDataset(mdr);

        MarketContext marketContext = new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                clock,
                Map.of(timeFrame, dataset),
                new HashMap<>()
        );

        OpinionContext opinionContext = new OpinionContext(
                null,
                UserProfile.builder().riskProfile(RiskProfile.MEDIUM).build(),
                marketContext,
                new HashMap<>(),
                clock
        );

        MarketOpinion marketOpinion = marketOpinionRegistry.get(DefaultMarketOpinion.class.getSimpleName());
        return captureOpinionEvent(marketOpinion, opinionContext, marketOpinionParameters);
    }

    /**
     * S'abonne temporairement à l'{@link EventBus} pour capturer de façon synchrone
     * l'{@link OpinionEvent} publié par {@code decide()}, puis se désabonne pour ne pas polluer
     * les autres tests partageant le même contexte Spring (cf. {@code @DirtiesContext} sur cette
     * classe). {@link EventBus#publish} appelle les consumers de façon synchrone dans le même
     * thread, donc l'{@link AtomicReference} est garanti renseigné dès le retour de {@code decide()}.
     */
    private OpinionEvent captureOpinionEvent(
            MarketOpinion marketOpinion,
            OpinionContext opinionContext,
            MarketOpinionParameters marketOpinionParameters
    ) {
        AtomicReference<OpinionEvent> captured = new AtomicReference<>();
        Consumer<OpinionEvent> consumer = captured::set;

        eventBus.subscribe(OpinionEvent.class, consumer);
        try {
            marketOpinion.decide(opinionContext, marketOpinionParameters);
        } finally {
            eventBus.unsubscribe(OpinionEvent.class, consumer);
        }

        return captured.get();
    }

}