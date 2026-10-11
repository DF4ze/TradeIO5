package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Jobs du bench Rainbow (23:55 / 00:05 UTC)")
class RainbowLiveJobsTest {

    private static final Instant NOW = Instant.parse("2026-09-30T23:55:00Z");

    private final RainbowLiveExecutionService service = mock(RainbowLiveExecutionService.class);
    private final FixedDomainClock clock = new FixedDomainClock(NOW);

    @Test
    @DisplayName("Job 23:55 délègue à runPass(T2355, clock.now())")
    void job2355Delegates() {
        when(service.runPass(RainbowLivePass.T2355, NOW))
                .thenReturn(new RainbowLiveExecutionService.PassSummary(RainbowLivePass.T2355, LocalDate.of(2026, 9, 30), 3, 0, 0));
        new RainbowLivePass2355Job(service, clock).run();
        verify(service).runPass(RainbowLivePass.T2355, NOW);
    }

    @Test
    @DisplayName("Job 00:05 délègue à runPass(T0005, clock.now())")
    void job0005Delegates() {
        when(service.runPass(RainbowLivePass.T0005, NOW))
                .thenReturn(new RainbowLiveExecutionService.PassSummary(RainbowLivePass.T0005, LocalDate.of(2026, 9, 29), 3, 0, 0));
        new RainbowLivePass0005Job(service, clock).run();
        verify(service).runPass(RainbowLivePass.T0005, NOW);
    }

    @Test
    @DisplayName("@Scheduled : zone UTC explicite, cron piloté par les propriétés tradeio.rainbow-live.*")
    void scheduledAnnotations() throws Exception {
        Scheduled s1 = RainbowLivePass2355Job.class.getMethod("run").getAnnotation(Scheduled.class);
        Scheduled s2 = RainbowLivePass0005Job.class.getMethod("run").getAnnotation(Scheduled.class);
        assertEquals("UTC", s1.zone());
        assertEquals("UTC", s2.zone());
        assertEquals("${tradeio.rainbow-live.pass-2355-cron:-}", s1.cron());
        assertEquals("${tradeio.rainbow-live.pass-0005-cron:-}", s2.cron());
    }

    @Component
    static class ProbeJobs {
        @Scheduled(cron = RainbowLiveDefaultPresets.PASS_2355_CRON, zone = RainbowLiveDefaultPresets.SCHEDULER_ZONE)
        public void a() {
        }

        @Scheduled(cron = RainbowLiveDefaultPresets.PASS_0005_CRON, zone = RainbowLiveDefaultPresets.SCHEDULER_ZONE)
        public void b() {
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
        ProbeJobs probeJobs() {
            return new ProbeJobs();
        }
    }

    @Test
    @DisplayName("Sans override (défaut '-') aucune tâche planifiée n'est enregistrée")
    void disabledByDefault() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(ProbeConfig.class)) {
            assertTrue(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
        }
    }

    @Test
    @DisplayName("Avec les 2 crons cibles, les 2 tâches sont enregistrées")
    void enabledWithCrons() {
        System.setProperty(RainbowLiveDefaultPresets.PASS_2355_CRON_PROPERTY, "0 55 23 * * *");
        System.setProperty(RainbowLiveDefaultPresets.PASS_0005_CRON_PROPERTY, "0 5 0 * * *");
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(ProbeConfig.class)) {
            assertEquals(2, ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
        } finally {
            System.clearProperty(RainbowLiveDefaultPresets.PASS_2355_CRON_PROPERTY);
            System.clearProperty(RainbowLiveDefaultPresets.PASS_0005_CRON_PROPERTY);
        }
    }
}
