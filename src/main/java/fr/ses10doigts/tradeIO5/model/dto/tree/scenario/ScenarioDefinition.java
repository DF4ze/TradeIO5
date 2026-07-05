package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;

import java.time.Instant;
import java.util.Optional;

public record ScenarioDefinition(
    ScenarioType type,
    ScenarioOwner owner,
    Optional<String> symbol,
    OpinionScope scope,
    Instant createdAt
) {}