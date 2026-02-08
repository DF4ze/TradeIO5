package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;

import java.time.Instant;
import java.util.Optional;

public record ScenarioDefinition(
    ScenarioType type,
    ScenarioOwner owner,
    Optional<String> symbol,
    Instant createdAt
) {}