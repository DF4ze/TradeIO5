package fr.ses10doigts.tradeIO5.model.dto.event.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Palier 3, étape 3 : DecisionCreatedCause transporte désormais les ActionStep d'origine de la
 * Decision créée (préparation de la persistance, étape 4). Test trivial et direct, sans passer
 * par tout le flux événementiel (DecisionEngine), pour documenter l'intention du champ.
 */
@DisplayName("DecisionCreatedCause - UT")
class DecisionCreatedCauseTest {

    @Test
    void actionSteps_returnsExactlyTheProvidedList() {
        List<ActionStep> steps = List.of(
                new ActionStep("step-1", ExecutionAction.BUY, BigDecimal.ONE, null),
                new ActionStep("step-2", ExecutionAction.SELL, BigDecimal.TEN, 42L)
        );

        DecisionCreatedCause cause = new DecisionCreatedCause("decision-1", "reason", steps);

        assertEquals("decision-1", cause.decisionId());
        assertEquals("reason", cause.reason());
        assertSame(steps, cause.actionSteps());
    }
}
