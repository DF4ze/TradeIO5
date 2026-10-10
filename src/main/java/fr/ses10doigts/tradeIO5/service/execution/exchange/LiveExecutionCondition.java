package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Vraie seulement si {@code tradeio.execution.mode=LIVE} <b>et</b> {@code tradeio.execution.live-unlocked=true}. */
public class LiveExecutionCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty(ExecutionDefaults.MODE_PROPERTY, "DRY_RUN");
        String unlocked = context.getEnvironment().getProperty(ExecutionDefaults.LIVE_UNLOCKED_PROPERTY, "false");
        return "LIVE".equalsIgnoreCase(mode.trim()) && Boolean.parseBoolean(unlocked.trim());
    }
}
