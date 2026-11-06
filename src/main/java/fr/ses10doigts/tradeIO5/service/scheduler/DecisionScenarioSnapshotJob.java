package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.doctor.JobTrigger;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.doctor.JobOutcome;
import fr.ses10doigts.tradeIO5.service.doctor.JobRunRecorder;

import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionScenarioSnapshotService;
import fr.ses10doigts.tradeIO5.service.tree.decision.SnapshotResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Photo quotidienne planifiée (Palier 3, étape 4). <b>Désactivée par défaut en prod</b> — même
 * logique que le scheduler de décision resté postposé (décision Clem, cf. prompt d'implémentation
 * de cette étape) : {@code tradeio.decision.snapshot-cron} vaut {@link
 * org.springframework.scheduling.annotation.Scheduled#CRON_DISABLED} ({@code "-"}) par défaut,
 * valeur spéciale documentée par le framework pour désactiver l'enregistrement de la tâche planifiée
 * (vérifié empiriquement, cf. {@code DecisionScenarioSnapshotJobTest}, pas seulement supposé).
 * Déclenchement manuel via {@link fr.ses10doigts.tradeIO5.controller.DecisionScenarioSnapshotAdminController}
 * (patron {@code EtfFlowAdminController}).
 */
@Component
@RequiredArgsConstructor
public class DecisionScenarioSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(DecisionScenarioSnapshotJob.class);

    private final DecisionScenarioSnapshotService snapshotService;
    private final JobRunRecorder recorder;

    @Scheduled(cron = "${tradeio.decision.snapshot-cron:-}")
    public void takeDailySnapshot() {
        SnapshotResult result = recorder.run(DoctorDefaults.JOB_SNAPSHOT, JobTrigger.CRON, snapshotService::takeSnapshot, r -> 0);
        log.info("DecisionScenarioSnapshotJob: photo planifiée prise ({} scénario(s), {} décision(s), {}).",
                result.scenarioCount(), result.decisionCount(), result.snapshotAt());
    }
}
