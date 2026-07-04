package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;

import java.util.Optional;

public record ScenarioKey(
        ScenarioOwner owner,
        ScenarioType type,
        Optional<String> symbol
) {}
