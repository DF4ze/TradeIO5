package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.MarketAction;

import java.time.Instant;

public record ActionIntent(
    MarketAction action,
    double confidence,
    String scenarioId,
    String reason,
    Instant createdAt
) {}
