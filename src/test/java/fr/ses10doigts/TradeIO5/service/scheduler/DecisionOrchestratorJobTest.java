package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionOrchestrator;
import fr.ses10doigts.tradeIO5.service.tree.decision.OrchestrationResult;
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
 * Palier 3, étape 7. Même patron que {@code UserArchivalJobTest} (étape 6) : vérifie la délégation
 * au service, et que la valeur par défaut de {@code tradeio.decision.orchestrator-cron}
 * ({@code Scheduled#CRON_DISABLED}, {@code "-"}) désactive bien l'enregistrement de la tâche
 * planifiée — vérifié empiriquement, pas seulement supposé.
 */
@DisplayName("DecisionOrchestratorJob")
class DecisionOrchestratorJobTest {

    @Test
    @DisplayName("runCycle() délègue à DecisionOrchestrator.runCycle()")
    void runCycle_delegatesToOrchestrator() {
        DecisionOrchestrator orchestrator = mock(DecisionOrchestrator.class);
        when(orchestrator.runCycle()).thenReturn(new OrchestrationResult(0, 0, 0, 0, Instant.now()));

        DecisionOrchestratorJob job = new DecisionOrchestratorJob(orchestrator);
        job.runCycle();

        verify(orchestrator, times(1)).runCycle();
    }

    @Component
    static class ProbeJob {
        @Scheduled(cron = "${tradeio.decision.orchestrator-cron:-}")
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
                    "cron='-' (défaut de tradeio.decision.orchestrator-cron) ne doit enregistrer aucune tâche planifiée");
        }
    }
}
