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

    // Marge au-dessus de TrendAnalyzer.MIN_CANDLES (60) : le générateur MEMORY produit des séries
    // parfaitement linéaires (r²=1 dès la première fenêtre calculable), donc l'hystérésis franchit
    // le seuil d'entrée (±1/6) dès les tout premiers scores calculables ; 90 bougies laisse de la
    // marge sans dépendre d'un pile-poil sur le minimum requis.
    private static final int LOOKBACK = 90;

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
     * (3x LINEAR_REGRESSION + hystérésis depuis sa réécriture Étape 8, {@code docs/prompts/
     * prompt-implementation-trend-unifie-etape8-regression-hysteresis.md}) branchée dans
     * {@link DefaultMarketOpinion} (scope {@code LOCAL}) via {@link StrategyAggregator}. On
     * s'abonne réellement à l'{@link OpinionEvent} publié par l'{@link EventBus} pour vérifier son
     * contenu plutôt que de se contenter de constater que {@code decide()} ne plante pas.
     * <p>
     * Les scénarios haussier/baissier/plat ci-dessous utilisent le générateur MEMORY monotone
     * ({@code TrendType.UPTREND}/{@code DOWNTREND}/{@code FLAT}) : contrairement à l'ancien
     * SWING_STRUCTURE (qui exigeait des pivots confirmés, cf. javadoc historique de cette classe),
     * la régression profite justement d'une tendance strictement monotone (r²=1) pour saturer le
     * score dès les premières fenêtres calculables.
     * <p>
     * Les 3 scénarios utilisent {@link TimeFrame#H1} (et non D1) : {@code H1} est le TimeFrame de
     * base du {@code Bucket} (cf. {@code MarketDatasetEngine}/{@code Bucket.BASE_TIME_FRAME}) —
     * demander un TimeFrame supérieur (D1) force une conversion de lookBack vers H1 (facteur 24),
     * et le générateur MEMORY appliquant un pas absolu (+-1) par bougie base plutôt que relatif, la
     * série obtenue peut dériver jusqu'à des prix négatifs sur un long historique une fois ramenée
     * en D1 — cas dégénéré propre au générateur synthétique de test (jamais rencontré sur un vrai
     * flux de marché), qui inverse le signe de {@code normalizedSlope} (pente négative / prix
     * négatif = signal positif) et fait apparaître un régime UP sur un DOWNTREND. Utiliser H1
     * directement (aucune conversion, aucune dérive) évite ce cas dégénéré.
     */
    @Test
    @DisplayName("Chaîne complète TrendConfirmation -> Opinion LOCAL : tendance haussière soutenue -> régime UP -> BULLISH")
    void trendConfirmationFullChainTest_bullConfirmed() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategyParametersFactory.TrendConfirmationParam param =
                StrategyParametersFactory.TrendConfirmationParam.defaults(TimeFrame.H1);
        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        OpinionEvent event = decideAndCapture(
                "trendOpinionBull", TimeFrame.H1, TrendType.UPTREND, marketOpinionParameters);

        assertNotNull(event, "OpinionEvent should have been published");
        assertEquals(OpinionScope.LOCAL, event.getScope());
        assertEquals(SignalType.BULLISH, event.getWeightedSignal());
        assertTrue(event.getScore() > 0);
    }

    @Test
    @DisplayName("Chaîne complète TrendConfirmation -> Opinion LOCAL : tendance baissière soutenue -> régime DOWN -> BEARISH")
    void trendConfirmationFullChainTest_bearConfirmed() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategyParametersFactory.TrendConfirmationParam param =
                StrategyParametersFactory.TrendConfirmationParam.defaults(TimeFrame.H1);
        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        OpinionEvent event = decideAndCapture(
                "trendOpinionBear", TimeFrame.H1, TrendType.DOWNTREND, marketOpinionParameters);

        assertNotNull(event, "OpinionEvent should have been published");
        assertEquals(OpinionScope.LOCAL, event.getScope());
        assertEquals(SignalType.BEARISH, event.getWeightedSignal());
        assertTrue(event.getScore() < 0);
    }

    @Test
    @DisplayName("Chaîne complète TrendConfirmation -> Opinion LOCAL : marché plat -> régime RANGE -> NEUTRAL")
    void trendConfirmationFullChainTest_flat() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategyParametersFactory.TrendConfirmationParam param =
                StrategyParametersFactory.TrendConfirmationParam.defaults(TimeFrame.H1);
        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        OpinionEvent event = decideAndCapture(
                "trendOpinionFlat", TimeFrame.H1, TrendType.FLAT, marketOpinionParameters);

        assertNotNull(event, "OpinionEvent should have been published");
        assertEquals(OpinionScope.LOCAL, event.getScope());
        assertEquals(SignalType.NEUTRAL, event.getWeightedSignal());
    }

    /**
     * Construit un dataset MEMORY mono-timeframe (source {@code TrendType}, via
     * {@link MarketDatasetEngine}), l'appelle à travers {@code decide()}, et capture réellement
     * l'{@link OpinionEvent} publié.
     */
    private OpinionEvent decideAndCapture(
            String datasetSymbol,
            TimeFrame timeFrame,
            TrendType scenario,
            MarketOpinionParameters marketOpinionParameters
    ) {
        MarketDatasetRequest mdr = new MarketDatasetRequest(datasetSymbol, timeFrame, LOOKBACK, Instant.now(), MarketDataSource.MEMORY, scenario);
        MarketDataset dataset = marketDatasetEngine.getDataset(mdr);

        return decideAndCaptureWithDataset(timeFrame, dataset, marketOpinionParameters);
    }

    private OpinionEvent decideAndCaptureWithDataset(
            TimeFrame timeFrame,
            MarketDataset dataset,
            MarketOpinionParameters marketOpinionParameters
    ) {
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
