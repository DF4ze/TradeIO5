package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.tree.decision.ArchivalResult;
import fr.ses10doigts.tradeIO5.service.tree.decision.UserArchivalService;
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
 * Palier 3, étape 6. Même patron que {@code DecisionScenarioSnapshotJobTest} (étape 4) : vérifie la
 * délégation au service, et que la valeur par défaut de {@code tradeio.decision.archival-cron}
 * ({@code Scheduled#CRON_DISABLED}, {@code "-"}) désactive bien l'enregistrement de la tâche
 * planifiée — vérifié empiriquement, pas seulement supposé.
 */
@DisplayName("UserArchivalJob")
class UserArchivalJobTest {

    @Test
    @DisplayName("archiveInactiveUsers() délègue à UserArchivalService.archiveInactiveUsers()")
    void archiveInactiveUsers_delegatesToService() {
        UserArchivalService archivalService = mock(UserArchivalService.class);
        when(archivalService.archiveInactiveUsers()).thenReturn(new ArchivalResult(0, Instant.now()));

        UserArchivalJob job = new UserArchivalJob(archivalService);
        job.archiveInactiveUsers();

        verify(archivalService, times(1)).archiveInactiveUsers();
    }

    @Component
    static class ProbeJob {
        @Scheduled(cron = "${tradeio.decision.archival-cron:-}")
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
                    "cron='-' (défaut de tradeio.decision.archival-cron) ne doit enregistrer aucune tâche planifiée");
        }
    }
}
