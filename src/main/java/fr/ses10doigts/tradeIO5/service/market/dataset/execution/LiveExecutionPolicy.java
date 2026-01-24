package fr.ses10doigts.tradeIO5.service.market.dataset.execution;

import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;

public class LiveExecutionPolicy implements ExecutionPolicy {
    public boolean accept(CompletenessLevel level) {
        return level == CompletenessLevel.COMPLETE
            || level == CompletenessLevel.PARTIAL_LAST;
    }
}
