package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;

import java.time.Instant;

public record ActionIntent(
    MarketIntentAction action,
    double confidence,
    String scenarioId,
    String reason,
    Instant createdAt
) {}
