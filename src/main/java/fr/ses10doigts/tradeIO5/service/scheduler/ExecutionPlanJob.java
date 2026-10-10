package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Plan d'ordres dry-run juste après la passe 23:55 UTC (lit les runs en base : le bench ne dépend pas du plan).
 * <b>Désactivé par défaut</b> ({@code tradeio.execution.plan-cron=-}), cron cible {@code 0 58 23 * * *}, {@code zone = "UTC"}
 * explicite. Déclenchement manuel : {@code POST /api/admin/execution/plan}. Rien n'est envoyé à un exchange.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionPlanJob {

    private final OrderPlanService planService;
    private final DomainClock clock;

    @Scheduled(cron = ExecutionDefaults.PLAN_CRON, zone = ExecutionDefaults.SCHEDULER_ZONE)
    public void run() {
        Instant now = clock.now();
        log.info("ExecutionPlanJob : {}", planService.planAll(RainbowLiveExecutionService.dayFor(RainbowLivePass.T2355, now),
                RainbowLivePass.T2355));
    }
}
