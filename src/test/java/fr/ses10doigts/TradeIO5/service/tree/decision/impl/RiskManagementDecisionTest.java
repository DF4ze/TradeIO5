package fr.ses10doigts.tradeIO5.service.tree.decision.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.*;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.DecisionAction;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.RiskProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.decision.Decision;
import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionRegistry;
import fr.ses10doigts.tradeIO5.service.tree.helper.DecisionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@SpringBootTest
class RiskManagementDecisionTest {

    @Autowired
    private StrategyRegistry strategyRegistry;
    @Autowired
    private DecisionRegistry decisionRegistry;
    @Autowired
    private MarketDatasetEngine marketDatasetEngine;

    @BeforeEach
    void setUp() {
    }

    @Test
    void testDecisionWithDifferentRiskProfiles() {
        // --- Préparation des StrategySignal simulés ---
        StrategySignal buyStrong = StrategySignal.simple(SignalType.BULLISH, 1.0, 1.0);
        StrategySignal sellWeak = StrategySignal.simple(SignalType.BEARISH, -0.4, 0.2);
        StrategySignal holdNeutral = StrategySignal.simple(SignalType.NEUTRAL, 0.2, 0.5);

        List<StrategySignal> signals = List.of(buyStrong, sellWeak, holdNeutral);

        // --- Paramètres génériques pour chaque profil ---
        DecisionParameters lowParams = DecisionParameters.builder()
                .riskProfile(RiskProfile.LOW)
                .build();

        DecisionParameters mediumParams = DecisionParameters.builder()
                .riskProfile(RiskProfile.MEDIUM)
                .build();

        DecisionParameters highParams = DecisionParameters.builder()
                .riskProfile(RiskProfile.HIGH)
                .build();

        // --- Contexte minimal pour la décision ---
        DecisionContext context = new DecisionContext(
                WalletSnapshot.builder().build(),
                UserProfile.builder().build(),
                MarketContext.builder().build(),
                null
        );

        // --- Instanciation de la décision avec une liste vide de strategies
        // car on va injecter directement les signaux ---
        RiskManagementDecision decision = new RiskManagementDecision();

        // --- Test profil LOW ---
        DecisionResult lowResult = decision.interpretSignals(signals, context, lowParams);
        System.out.println("LOW profile action: " + lowResult.getAction());
        assertEquals(DecisionAction.HOLD, lowResult.getAction());

        // --- Test profil MEDIUM ---
        DecisionResult mediumResult = decision.interpretSignals(signals, context, mediumParams);
        System.out.println("MEDIUM profile action: " + mediumResult.getAction());
        assertEquals(DecisionAction.HOLD, mediumResult.getAction());

        // --- Test profil HIGH ---
        DecisionResult highResult = decision.interpretSignals(signals, context, highParams);
        System.out.println("HIGH profile action: " + highResult.getAction());
        assertEquals(DecisionAction.BUY, highResult.getAction());
    }

    @Test
    void riskManagementFullChainTest(){
        // Build params
        Strategy strategy = strategyRegistry.get(DoubleRsiStrategy.class.getSimpleName());
        StrategyParametersFactory.RsiParam fastRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.H1, 12, 70, 30);
        StrategyParametersFactory.RsiParam slowRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.M1, 24, 60, 28);

        DecisionParameters decisionParameters = DecisionParametersFactory.buildRiskManagementParamWithDoubleRSI(strategy, slowRsiParam, fastRsiParam, RiskProfile.MEDIUM);

        // Building dataset & MarketContext
        MarketDatasetRequest mdrSlow = new MarketDatasetRequest("slowTF", TimeFrame.M1, 50, null, MarketDataSource.MEMORY, MarketScenario.UPTREND);
        MarketDatasetRequest mdrFast = new MarketDatasetRequest("fastTF", TimeFrame.H1, 50, null, MarketDataSource.MEMORY, MarketScenario.UPTREND);
        MarketDataset slowDataset = marketDatasetEngine.refresh(mdrSlow);
        MarketDataset fastDataset = marketDatasetEngine.refresh(mdrFast);

        MarketContext marketContext = MarketContext.builder()
                .symbol("BTCUSDT")
                .series(Map.of(
                        TimeFrame.M1, slowDataset,
                        TimeFrame.H1, fastDataset))
                .lastPrice(new BigDecimal("42000"))
                .timestamp(Instant.now())
                .build();
        DecisionContext decisionContext =  DecisionContext.builder()
                .marketContext(marketContext)
                .userProfile(UserProfile.builder().riskProfile(RiskProfile.MEDIUM).build())
                .build();

        //
        Decision decision = decisionRegistry.get(RiskManagementDecision.class.getSimpleName());
        DecisionResult decide = decision.decide(decisionContext, decisionParameters);

        assertSame(DecisionAction.SELL, decide.getAction());


    }

}