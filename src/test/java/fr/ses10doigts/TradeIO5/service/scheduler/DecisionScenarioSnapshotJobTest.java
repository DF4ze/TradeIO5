package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionScenarioSnapshotService;
import fr.ses10doigts.tradeIO5.service.tree.decision.SnapshotResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Palier 3, étape 4 (étape 7 du prompt d'implémentation).
 * <p>
 * Deux préoccupations distinctes :
 * <ul>
 *     <li>{@code takeDailySnapshot()} délègue bien à {@link DecisionScenarioSnapshotService}
 *     (patron {@code EtfFlowHistorizationJobTest}) ;</li>
 *     <li>la valeur par défaut de {@code tradeio.decision.snapshot-cron} ({@code
 *     Scheduled#CRON_DISABLED}, {@code "-"}) désactive bien l'enregistrement de la tâche planifiée
 *     — vérifié empiriquement via un mini contexte Spring + {@link
 *     ScheduledAnnotationBeanPostProcessor#getScheduledTasks()}, pas seulement supposé (cf. consigne
 *     explicite du prompt d'implémentation).</li>
 * </ul>
 */
@DisplayName("DecisionScenarioSnapshotJob")
class DecisionScenarioSnapshotJobTest {

    @Test
    @DisplayName("takeDailySnapshot() délègue à DecisionScenarioSnapshotService.takeSnapshot()")
    void takeDailySnapshot_delegatesToService() {
        DecisionScenarioSnapshotService snapshotService = mock(DecisionScenarioSnapshotService.class);
        when(snapshotService.takeSnapshot()).thenReturn(new SnapshotResult(1, 1, Instant.now()));

        DecisionScenarioSnapshotJob job = new DecisionScenarioSnapshotJob(snapshotService);
        job.takeDailySnapshot();

        verify(snapshotService, times(1)).takeSnapshot();
    }

    @Component
    static class ProbeJob {
        @Scheduled(cron = "${tradeio.decision.snapshot-cron:-}")
        public void run() {
        }
    }

    @Configuration
    @EnableScheduling
    static class ProbeConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ProbeJob probeJob() {
            return new ProbeJob();
        }
    }

    @Test
    @DisplayName("Sans override de propriété, cron='-' (valeur par défaut) n'enregistre aucune tâche planifiée")
    void cronDisabledByDefault_registersNoScheduledTask() {
        assertEquals(Scheduled.CRON_DISABLED, "-",
                "Sanity check : CRON_DISABLED doit rester \"-\" pour que la valeur par défaut de la propriété reste correcte");

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(ProbeConfig.class)) {
            ScheduledAnnotationBeanPostProcessor postProcessor =
                    ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);

            assertTrue(postProcessor.getScheduledTasks().isEmpty(),
                    "cron='-' (défaut de tradeio.decision.snapshot-cron) ne doit enregistrer aucune tâche planifiée");
        }
    }
}
