package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService.PlanSummary;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService.ResetTarget;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPlanTriggerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T22:27:00Z");
    private static final PlanSummary SUMMARY = new PlanSummary(LocalDate.of(2026, 11, 5), 1, 0, 0, 0, 0, 0);

    private final OrderPlanService planService = mock(OrderPlanService.class);
    private final RainbowLiveExecutionService executionService = mock(RainbowLiveExecutionService.class);
    private final DomainClock clock = mock(DomainClock.class);
    private final AdminActionLogService journal = mock(AdminActionLogService.class);
    private final AdminPlanTriggerService service = new AdminPlanTriggerService(planService, executionService, clock, journal);
    private final User user = new User();

    @Test
    void resetOfOpenDayRecomputesSignalBeforeReplanning() {
        when(clock.now()).thenReturn(NOW);
        when(planService.resetTarget(24L)).thenReturn(new ResetTarget(user, LocalDate.of(2026, 11, 5)));
        when(planService.resetPlan(24L)).thenReturn(SUMMARY);

        service.reset(24L, "admin");

        InOrder order = inOrder(executionService, planService);
        order.verify(executionService).recomputeDay(user, NOW, LocalDate.of(2026, 11, 5));
        order.verify(planService).resetPlan(24L);
    }

    @Test
    void resetOfClosedDayKeepsStoredRun() {
        when(clock.now()).thenReturn(NOW);
        when(planService.resetTarget(7L)).thenReturn(new ResetTarget(user, LocalDate.of(2026, 11, 3)));
        when(planService.resetPlan(7L)).thenReturn(SUMMARY);

        service.reset(7L, "admin");

        verify(executionService, never()).recomputeDay(any(), any(), any());
        verify(planService).resetPlan(7L);
    }

    @Test
    void notResettablePlanRecomputesNothing() {
        when(planService.resetTarget(9L)).thenThrow(new IllegalArgumentException("non réinitialisable"));

        assertThrows(IllegalArgumentException.class, () -> service.reset(9L, "admin"));

        verify(executionService, never()).recomputeDay(any(), any(), any());
        verify(planService, never()).resetPlan(any());
        verify(journal).record(anyString(), any(), anyString(), any(), any());
    }
}
