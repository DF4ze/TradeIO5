package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderExecutor;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exécution des plans d'ordres de la passe 23:55 UTC. <b>Désactivé par défaut</b> ({@code tradeio.execution.run-cron=-}) ;
 * sans effet hors mode LIVE (l'exécuteur n'envoie rien). Déclenchement manuel : {@code POST /api/admin/execution/run}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionJob {

    private final OrderExecutor executor;
    private final DomainClock clock;

    @Scheduled(cron = ExecutionDefaults.RUN_CRON, zone = ExecutionDefaults.SCHEDULER_ZONE)
    public void run() {
        log.info("ExecutionJob : {}", executor.executeAll(RainbowLiveExecutionService.dayFor(RainbowLivePass.T2355, clock.now()),
                RainbowLivePass.T2355));
    }
}
