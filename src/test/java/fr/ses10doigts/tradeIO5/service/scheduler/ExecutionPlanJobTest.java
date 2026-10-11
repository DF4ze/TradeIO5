package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("ExecutionPlanJob : plan d'ordres dry-run après la passe 23:55 UTC")
class ExecutionPlanJobTest {

    private static final Instant NOW = Instant.parse("2026-10-09T23:58:00Z");

    @Test
    @DisplayName("Délègue à planAll(jour UTC, T2355)")
    void delegates() {
        OrderPlanService service = mock(OrderPlanService.class);

        new ExecutionPlanJob(service, new FixedDomainClock(NOW)).run();

        verify(service).planAll(LocalDate.of(2026, 10, 9), RainbowLivePass.T2355);
    }

    @Test
    @DisplayName("@Scheduled : zone UTC explicite, cron piloté par tradeio.execution.plan-cron")
    void annotation() throws Exception {
        Scheduled s = ExecutionPlanJob.class.getMethod("run").getAnnotation(Scheduled.class);
        assertEquals("UTC", s.zone());
        assertEquals("${tradeio.execution.plan-cron:-}", s.cron());
    }

    @Configuration
    @EnableScheduling
    static class ProbeConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ExecutionPlanJob job() {
            return new ExecutionPlanJob(mock(OrderPlanService.class), new FixedDomainClock(NOW));
        }
    }

    @Test
    @DisplayName("Désactivé par défaut (aucune tâche) ; activé avec le cron cible")
    void disabledByDefault() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(ProbeConfig.class)) {
            assertTrue(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
        }
        System.setProperty(ExecutionDefaults.PLAN_CRON_PROPERTY, "0 58 23 * * *");
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(ProbeConfig.class)) {
            assertEquals(1, ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
        } finally {
            System.clearProperty(ExecutionDefaults.PLAN_CRON_PROPERTY);
        }
    }
}
