package fr.ses10doigts.tradeIO5.service.market.dataset.execution;

import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;

public interface ExecutionPolicy {
    boolean accept(CompletenessLevel level);
}
