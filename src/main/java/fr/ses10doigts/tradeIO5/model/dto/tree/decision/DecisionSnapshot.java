package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;

import java.time.Instant;

public record DecisionSnapshot(
        String decisionId,
        String symbol,
        String accountId,
        DecisionType type,
        Instant createdAt
) {}
