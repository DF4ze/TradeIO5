package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Au démarrage (mode LIVE uniquement) : réconcilie les plans restés {@code EXECUTING} après un arrêt brutal. Lecture
 * seule côté exchange ({@link OrderExecutor#reconcile}) : n'envoie jamais d'ordre.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionStartupReconciler implements ApplicationRunner {

    private final ExecutionSettings settings;
    private final RainbowLiveOrderPlanRepository plans;
    private final OrderExecutor executor;

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.liveExecution()) {
            return;
        }
        for (RainbowLiveOrderPlan plan : plans.findByStatus(PlanStatus.EXECUTING)) {
            try {
                log.info("Réconciliation au démarrage : plan {}", plan.getId());
                executor.reconcile(plan.getId());
            } catch (RuntimeException e) {
                log.error("Réconciliation au démarrage : échec plan {}", plan.getId(), e);
            }
        }
    }
}
