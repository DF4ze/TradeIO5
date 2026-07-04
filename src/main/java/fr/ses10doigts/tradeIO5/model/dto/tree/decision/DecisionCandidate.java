package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;

import java.math.BigDecimal;
import java.time.Instant;

public record DecisionCandidate(
        String symbol,
        DecisionType type,
        ExecutionAction action,
        double confidence,
        BigDecimal quantity,
        String reason,
        ScenarioOwner owner,
        Instant createdAt
) {}
