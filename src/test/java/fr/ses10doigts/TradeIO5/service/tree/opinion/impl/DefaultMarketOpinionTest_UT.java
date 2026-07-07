package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.UserProfile;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Cf. MarketDatasetEngineSpringTest : le MarketDatasetCache (singleton Spring) est indexé
// par flux natif (symbol + timeFrame + source + providerParam) et partagé entre toutes les
// classes de test utilisant le même contexte Spring ("fastTF"/H1/UPTREND est aussi utilisé
// ailleurs). On isole le contexte par méthode pour éviter toute pollution croisée.
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

    /**
     * Chaîne complète Indicator -&gt; Strategy -&gt; Opinion pour {@link TrendConfirmationStrategy}
     * (EMA + ADX + RSI) branchée dans {@link DefaultMarketOpinion} (scope {@code LOCAL}) via
     * {@link StrategyAggregator}. On s'abonne réellement à l'{@link OpinionEvent} publié par
     * l'{@link EventBus} pour vérifier son contenu plutôt que de se contenter de constater que
     * {@code decide()} ne plante pas.
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