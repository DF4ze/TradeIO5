package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionOrchestrator;
import fr.ses10doigts.tradeIO5.service.tree.decision.OrchestrationResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cycle d'orchestration planifié (Palier 3, étape 7). <b>Désactivé par défaut en prod</b> — même
 * patron que {@link UserArchivalJob} (étape 6)/{@code DecisionScenarioSnapshotJob} (étape 4) :
 * {@code tradeio.decision.orchestrator-cron} vaut
 * {@link org.springframework.scheduling.annotation.Scheduled#CRON_DISABLED} ({@code "-"}) par
 * défaut. Déclenchement manuel via
 * {@link fr.ses10doigts.tradeIO5.controller.DecisionOrchestratorAdminController} (même patron que
 * {@code UserArchivalAdminController}).
 */
@Component
@RequiredArgsConstructor
public class DecisionOrchestratorJob {

    private static final Logger log = LoggerFactory.getLogger(DecisionOrchestratorJob.class);

    private final DecisionOrchestrator orchestrator;

    @Scheduled(cron = "${tradeio.decision.orchestrator-cron:-}")
    public void runCycle() {
        OrchestrationResult result = orchestrator.runCycle();
        log.info("DecisionOrchestratorJob: cycle planifié exécuté ({} signal(aux), {} owner(s) traité(s), "
                        + "{} owner(s) ignoré(s), {}).",
                result.signalsComputed(), result.usersProcessed(), result.usersSkippedLocked(), result.runAt());
    }
}
