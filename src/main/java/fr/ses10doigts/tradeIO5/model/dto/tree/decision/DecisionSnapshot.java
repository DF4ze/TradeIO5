package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;

import java.time.Instant;

public record DecisionSnapshot(
        String decisionId,
        String symbol,
        ScenarioOwner owner,
        DecisionType type,
        Instant createdAt
) {}
