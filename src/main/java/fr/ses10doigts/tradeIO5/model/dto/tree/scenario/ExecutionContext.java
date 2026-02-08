package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventStore;

public record ExecutionContext(
    ExecutionMode mode,
    EventStore eventStore
) {}