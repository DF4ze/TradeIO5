package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

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
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinionRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

// Cf. MarketDatasetEngineSpringTest / DoubleRsiStrategyTest : le MarketDatasetCache
// (singleton Spring) est indexé par flux natif (symbol + timeFrame + source +
// providerParam) et partagé entre toutes les classes de test utilisant le même contexte
// Spring ("fastTF"/H1/UPTREND est aussi utilisé ailleurs). On isole le contexte par méthode
// pour éviter toute pollution croisée.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Decision - Risk Management - UT")
@SpringBootTest
class DefaultMarketOpinionTest_UT {

    @Autowired
    private StrategyRegistry strategyRegistry;
    @Autowired
    private MarketOpinionRegistry marketOpinionRegistry;
    @Autowired
    private MarketDatasetEngine marketDatasetEngine;

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

}