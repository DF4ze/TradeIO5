package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

public record ExecutionResult(
        String orderId,
        double executedQuantity,
        double averagePrice,
        double fees
) {}
