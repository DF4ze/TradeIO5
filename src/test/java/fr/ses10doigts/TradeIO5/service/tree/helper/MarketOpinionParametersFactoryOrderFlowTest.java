package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Vérifie le branchement de {@link OrderFlowStrategy} (étude
 * "nouvelles-opinions-indicateurs-non-branches" §4), même patron que
 * {@code MarketOpinionParametersFactoryMovementQualificationTest}. Test de construction pure (pas
 * de réseau, pas d'appel Coinalyze/Binance réel).
 */
@DisplayName("MarketOpinionParametersFactory - OrderFlow")
class MarketOpinionParametersFactoryOrderFlowTest {

    private static final TimeFrame TF = TimeFrame.H1;
    private static final ApiCredentialDTO COINALYZE_CREDENTIAL =
            new ApiCredentialDTO(WebProviderCode.COINALYZE, "fake-key", null, "https://api.coinalyze.net/v1");

    @Test
    @DisplayName("buildOrderFlowStrategyParam : 2 IndicatorKey (ORDER_BOOK sans credential, LIQUIDATIONS avec credential Coinalyze), 6 seuils numériques")
    void buildOrderFlowStrategyParam_shape() {
        StrategyParametersFactory.OrderFlowParam param = StrategyParametersFactory.OrderFlowParam.defaults(TF);

        StrategyParameters params = StrategyParametersFactory.buildOrderFlowStrategyParam(param, COINALYZE_CREDENTIAL);

        assertEquals(2, params.getIndicatorParameters().size());

        long orderBookCount = 0, liquidationsCount = 0;
        for (var entry : params.getIndicatorParameters().entrySet()) {
            IndicatorKey key = entry.getKey();
            assertEquals(TF, key.getTimeFrame());
            switch (key.getType()) {
                case ORDER_BOOK -> {
                    orderBookCount++;
                    assertNull(entry.getValue().getCredential(), "ORDER_BOOK est public, ne doit pas porter de credential");
                }
                case LIQUIDATIONS -> {
                    liquidationsCount++;
                    assertSame(COINALYZE_CREDENTIAL, entry.getValue().getCredential());
                }
                default -> throw new AssertionError("Type inattendu : " + key.getType());
            }
        }
        assertEquals(1, orderBookCount);
        assertEquals(1, liquidationsCount);

        assertEquals(6, params.getNumericParams().size());
        assertEquals(0.3, params.getNumericParams().get(OrderFlowStrategy.P_LIQUIDATION_SKEW_THRESHOLD));
        assertEquals(0.02, params.getNumericParams().get(OrderFlowStrategy.P_LIQUIDATION_VOLUME_RATIO_THRESHOLD));
    }

    @Test
    @DisplayName("buildOrderFlowStrategyParam : accepts() de la Strategy retourne vrai sur le résultat de la fabrique")
    void buildOrderFlowStrategyParam_acceptedByStrategy() {
        OrderFlowStrategy strategy = new OrderFlowStrategy(mock(IndicatorRegistry.class), mock(IndicatorEngine.class));

        StrategyParametersFactory.OrderFlowParam param = StrategyParametersFactory.OrderFlowParam.defaults(TF);
        StrategyParameters params = StrategyParametersFactory.buildOrderFlowStrategyParam(param, COINALYZE_CREDENTIAL);

        assertTrue(strategy.accepts(params));
    }

    @Test
    @DisplayName("buildLocalOpinionParamWithOrderFlow : 1 StrategyKey portant la Strategy et les params construits")
    void buildLocalOpinionParamWithOrderFlow_shape() {
        OrderFlowStrategy strategy = new OrderFlowStrategy(mock(IndicatorRegistry.class), mock(IndicatorEngine.class));

        StrategyParametersFactory.OrderFlowParam param = StrategyParametersFactory.OrderFlowParam.defaults(TF);

        MarketOpinionParameters opinionParams = MarketOpinionParametersFactory.buildLocalOpinionParamWithOrderFlow(
                strategy, param, COINALYZE_CREDENTIAL
        );

        assertNotNull(opinionParams.getStrategies());
        assertEquals(1, opinionParams.getStrategies().size());

        StrategyKey key = opinionParams.getStrategies().getFirst();
        assertSame(strategy, key.getStrategy());
        assertEquals(2, key.getParameters().getIndicatorParameters().size());
        assertTrue(key.getParameters().getIndicatorParameters().keySet().stream()
                .anyMatch(k -> k.getType() == IndicatorType.ORDER_BOOK));
    }
}
