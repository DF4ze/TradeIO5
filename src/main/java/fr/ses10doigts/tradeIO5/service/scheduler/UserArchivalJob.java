package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.tree.decision.ArchivalResult;
import fr.ses10doigts.tradeIO5.service.tree.decision.UserArchivalService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Archivage planifié (Palier 3, étape 6). <b>Désactivé par défaut en prod</b> — même patron que
 * {@link fr.ses10doigts.tradeIO5.service.scheduler.DecisionScenarioSnapshotJob} (étape 4) : {@code
 * tradeio.decision.archival-cron} vaut {@link org.springframework.scheduling.annotation.Scheduled#CRON_DISABLED}
 * ({@code "-"}) par défaut, valeur spéciale documentée par le framework pour désactiver
 * l'enregistrement de la tâche planifiée (déjà vérifiée empiriquement à l'étape 4). Déclenchement
 * manuel via {@link fr.ses10doigts.tradeIO5.controller.UserArchivalAdminController} (même patron que
 * {@code DecisionScenarioSnapshotAdminController}).
 */
@Component
@RequiredArgsConstructor
public class UserArchivalJob {

    private static final Logger log = LoggerFactory.getLogger(UserArchivalJob.class);

    private final UserArchivalService archivalService;

    @Scheduled(cron = "${tradeio.decision.archival-cron:-}")
    public void archiveInactiveUsers() {
        ArchivalResult result = archivalService.archiveInactiveUsers();
        log.info("UserArchivalJob: archivage planifié exécuté ({} utilisateur(s) archivé(s), {}).",
                result.archivedCount(), result.archivedAt());
    }
}
