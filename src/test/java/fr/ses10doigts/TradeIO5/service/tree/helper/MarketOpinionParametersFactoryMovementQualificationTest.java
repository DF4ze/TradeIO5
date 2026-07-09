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
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Vérifie le "branchement" de {@link MovementQualificationStrategy} ajouté le 2026-07-09 (audit
 * docs/etat-des-lieux-indicateurs-strategies-opinions.md §3) : jusqu'ici cette Strategy n'était
 * utilisable qu'en construisant les {@code StrategyParameters} à la main via {@code evaluate_strategy}
 * (MCP) — elle a maintenant une fabrique réutilisable équivalente à
 * {@code buildLocalOpinionParamWithTrendConfirmation}.
 * <p>
 * Test de construction pure (pas de réseau, pas d'appel Coinalyze réel) : vérifie la forme des
 * objets produits, pas le résultat d'une évaluation live.
 */
@DisplayName("MarketOpinionParametersFactory - MovementQualification")
class MarketOpinionParametersFactoryMovementQualificationTest {

    private static final TimeFrame TF = TimeFrame.H1;
    private static final ApiCredentialDTO COINALYZE_CREDENTIAL =
            new ApiCredentialDTO(WebProviderCode.COINALYZE, "fake-key", null, "https://api.coinalyze.net/v1");

    @Test
    @DisplayName("buildMovementQualificationStrategyParam : 3 IndicatorKey (OI/Funding/OBV), credential Coinalyze sur OI+Funding seulement, 8 seuils numériques")
    void buildMovementQualificationStrategyParam_shape() {
        StrategyParametersFactory.MovementQualificationParam param =
                StrategyParametersFactory.MovementQualificationParam.defaults(TF, 14.0);

        StrategyParameters params = StrategyParametersFactory.buildMovementQualificationStrategyParam(param, COINALYZE_CREDENTIAL);

        assertEquals(3, params.getIndicatorParameters().size());

        long oiCount = 0, fundingCount = 0, obvCount = 0;
        for (var entry : params.getIndicatorParameters().entrySet()) {
            IndicatorKey key = entry.getKey();
            assertEquals(TF, key.getTimeFrame());
            switch (key.getType()) {
                case OPEN_INTEREST -> {
                    oiCount++;
                    assertSame(COINALYZE_CREDENTIAL, entry.getValue().getCredential());
                }
                case FUNDING_RATE -> {
                    fundingCount++;
                    assertSame(COINALYZE_CREDENTIAL, entry.getValue().getCredential());
                }
                case OBV -> {
                    obvCount++;
                    assertNull(entry.getValue().getCredential(), "OBV est interne, ne doit pas porter de credential");
                    assertEquals(14.0, entry.getValue().getNumeric("period"));
                }
                default -> throw new AssertionError("Type inattendu : " + key.getType());
            }
        }
        assertEquals(1, oiCount);
        assertEquals(1, fundingCount);
        assertEquals(1, obvCount);

        assertEquals(8, params.getNumericParams().size());
        assertEquals(-0.10, params.getNumericParams().get(MovementQualificationStrategy.P_OI_DELTA_CASCADE_THRESHOLD));
        assertEquals(0.10, params.getNumericParams().get(MovementQualificationStrategy.P_OI_DELTA_BUILDUP_THRESHOLD));
        assertEquals(0.6, params.getNumericParams().get(MovementQualificationStrategy.P_FUNDING_BUILDUP_SIGNAL_THRESHOLD));
    }

    @Test
    @DisplayName("buildMovementQualificationStrategyParam : accepts() de la Strategy retourne vrai sur le résultat de la fabrique")
    void buildMovementQualificationStrategyParam_acceptedByStrategy() {
        MovementQualificationStrategy strategy =
                new MovementQualificationStrategy(mock(IndicatorRegistry.class), mock(IndicatorEngine.class));

        StrategyParametersFactory.MovementQualificationParam param =
                StrategyParametersFactory.MovementQualificationParam.defaults(TF, 14.0);
        StrategyParameters params = StrategyParametersFactory.buildMovementQualificationStrategyParam(param, COINALYZE_CREDENTIAL);

        assertTrue(strategy.accepts(params));
    }

    @Test
    @DisplayName("buildLocalOpinionParamWithMovementQualification : 1 StrategyKey portant la Strategy et les params construits")
    void buildLocalOpinionParamWithMovementQualification_shape() {
        MovementQualificationStrategy strategy =
                new MovementQualificationStrategy(mock(IndicatorRegistry.class), mock(IndicatorEngine.class));

        StrategyParametersFactory.MovementQualificationParam param =
                StrategyParametersFactory.MovementQualificationParam.defaults(TF, 14.0);

        MarketOpinionParameters opinionParams = MarketOpinionParametersFactory.buildLocalOpinionParamWithMovementQualification(
                strategy, param, COINALYZE_CREDENTIAL
        );

        assertNotNull(opinionParams.getStrategies());
        assertEquals(1, opinionParams.getStrategies().size());

        StrategyKey key = opinionParams.getStrategies().getFirst();
        assertSame(strategy, key.getStrategy());
        assertEquals(3, key.getParameters().getIndicatorParameters().size());
        assertTrue(key.getParameters().getIndicatorParameters().keySet().stream()
                .anyMatch(k -> k.getType() == IndicatorType.OPEN_INTEREST));
    }
}
