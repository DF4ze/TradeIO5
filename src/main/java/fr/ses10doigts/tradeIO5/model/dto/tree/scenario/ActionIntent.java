package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;

import java.math.BigDecimal;
import java.time.Instant;

public record ActionIntent(
    MarketIntentAction action,
    String symbol,
    BigDecimal quantity,
    double confidence,
    String scenarioId,
    String reason,
    Instant createdAt
) {}
