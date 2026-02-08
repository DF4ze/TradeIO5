package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;

public record DecisionCandidate(
        String symbol,
        DecisionType type,
        ExecutionAction action,
        double quantity
) {}
