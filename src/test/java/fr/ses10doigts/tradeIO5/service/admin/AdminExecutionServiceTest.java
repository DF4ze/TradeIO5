package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService.PlanSummary;
import fr.ses10doigts.tradeIO5.service.execution.run.ExecutionBlockReason;
import fr.ses10doigts.tradeIO5.service.execution.run.ExecutionResult;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderExecutor;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.scheduler.ExecutionJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Admin : réengagement automatique du kill switch et journal de chaque action")
class AdminExecutionServiceTest {

    private OrderExecutor executor;
    private ExecutionControlService control;
    private RainbowLiveBindingService bindings;
    private RainbowLiveOrderPlanRepository plans;
    private AdminActionLogService journal;
    private AdminExecutionService service;

    @BeforeEach
    void setUp() {
        executor = mock(OrderExecutor.class);
        control = mock(ExecutionControlService.class);
        bindings = mock(RainbowLiveBindingService.class);
        plans = mock(RainbowLiveOrderPlanRepository.class);
        journal = mock(AdminActionLogService.class);
        service = new AdminExecutionService(executor, control, bindings, plans, journal);
    }

    private void killSwitch(boolean engaged) {
        ExecutionControl c = ExecutionControl.initial(Instant.EPOCH);
        c.setKillSwitch(engaged);
        when(control.current()).thenReturn(c);
    }

    @Test
    @DisplayName("Succès : kill switch levé à l'appel => rengagé par 'auto-reengage' et AUTO_REENGAGE au journal")
    void reengagedAfterSuccess() {
        killSwitch(false);
        when(executor.execute(42L)).thenReturn(new ExecutionResult(42L, PlanStatus.EXECUTED, Map.of(), 1));

        service.run(42L, "clem");

        verify(control).setKillSwitch(true, ExecutionDefaults.AUTO_REENGAGE_ACTOR);
        verify(journal).record(eq("clem"), eq(AdminAction.RUN), eq("plan:42"), any(), eq(AdminActionOutcome.OK));
        verify(journal).record(eq(ExecutionDefaults.AUTO_REENGAGE_ACTOR), eq(AdminAction.AUTO_REENGAGE), eq("plan:42"), any(),
                eq(AdminActionOutcome.OK));
    }

    @Test
    @DisplayName("Blocage : rien d'envoyé => RUN REFUSED au journal, kill switch quand même rengagé")
    void reengagedAfterBlock() {
        killSwitch(false);
        when(executor.execute(7L)).thenReturn(ExecutionResult.blockedGlobally(7L, PlanStatus.PLANNED, ExecutionBlockReason.MODE_NOT_LIVE));

        ExecutionResult result = service.run(7L, "clem");

        assertEquals(0, result.sent());
        verify(journal).record(eq("clem"), eq(AdminAction.RUN), eq("plan:7"), any(), eq(AdminActionOutcome.REFUSED));
        verify(control).setKillSwitch(true, ExecutionDefaults.AUTO_REENGAGE_ACTOR);
    }

    @Test
    @DisplayName("Exception de l'exécuteur : propagée, ERROR au journal, kill switch rengagé")
    void reengagedAfterException() {
        killSwitch(false);
        IllegalStateException boom = new IllegalStateException("exchange en panne");
        when(executor.execute(9L)).thenThrow(boom);

        assertSame(boom, assertThrows(IllegalStateException.class, () -> service.run(9L, "clem")));

        verify(journal).record(eq("clem"), eq(AdminAction.RUN), eq("plan:9"), any(), eq(AdminActionOutcome.ERROR));
        InOrder order = inOrder(control, journal);
        order.verify(control).setKillSwitch(true, ExecutionDefaults.AUTO_REENGAGE_ACTOR);
        order.verify(journal).record(eq(ExecutionDefaults.AUTO_REENGAGE_ACTOR), eq(AdminAction.AUTO_REENGAGE), eq("plan:9"), any(),
                eq(AdminActionOutcome.OK));
    }

    @Test
    @DisplayName("Plan inconnu (IllegalArgumentException) : REFUSED, kill switch rengagé")
    void unknownPlanIsRefused() {
        killSwitch(false);
        when(executor.execute(5L)).thenThrow(new IllegalArgumentException("Plan inconnu : 5"));

        assertThrows(IllegalArgumentException.class, () -> service.run(5L, "clem"));

        verify(journal).record(eq("clem"), eq(AdminAction.RUN), eq("plan:5"), any(), eq(AdminActionOutcome.REFUSED));
        verify(control).setKillSwitch(true, ExecutionDefaults.AUTO_REENGAGE_ACTOR);
    }

    @Test
    @DisplayName("Kill switch déjà engagé à l'appel : aucun réengagement ni AUTO_REENGAGE")
    void noReengageWhenAlreadyEngaged() {
        killSwitch(true);
        when(executor.execute(1L)).thenReturn(ExecutionResult.blockedGlobally(1L, PlanStatus.PLANNED, ExecutionBlockReason.KILL_SWITCH));

        service.run(1L, "clem");

        verify(control, never()).setKillSwitch(anyBoolean(), any());
        verify(journal, never()).record(any(), eq(AdminAction.AUTO_REENGAGE), any(), any(), any());
    }

    @Test
    @DisplayName("Plan EXECUTING : l'action journalisée est RECONCILE")
    void reconcileIsRecognised() {
        killSwitch(false);
        RainbowLiveOrderPlan plan = RainbowLiveOrderPlan.builder().status(PlanStatus.EXECUTING).build();
        when(plans.findById(3L)).thenReturn(Optional.of(plan));
        when(executor.execute(3L)).thenReturn(new ExecutionResult(3L, PlanStatus.EXECUTED, Map.of(), 0));

        service.run(3L, "clem");

        verify(journal).record(eq("clem"), eq(AdminAction.RECONCILE), eq("plan:3"), any(), eq(AdminActionOutcome.OK));
        verify(control).setKillSwitch(true, ExecutionDefaults.AUTO_REENGAGE_ACTOR);
    }

    @Test
    @DisplayName("Kill switch et armement : journalisés avec l'auteur ; binding inconnu => REFUSED")
    void killSwitchAndArmAreJournaled() {
        service.setKillSwitch(false, "clem");
        verify(control).setKillSwitch(false, "clem");
        verify(journal).record(eq("clem"), eq(AdminAction.KILL_SWITCH), any(), any(), eq(AdminActionOutcome.OK));

        service.arm(8L, "clem");
        verify(bindings).arm(8L);
        verify(journal).record("clem", AdminAction.ARM_BINDING, "binding:8", null, AdminActionOutcome.OK);

        Mockito.doThrow(RainbowLiveBindingNotFoundException.binding(99L)).when(bindings).arm(99L);
        assertThrows(RainbowLiveBindingNotFoundException.class, () -> service.arm(99L, "clem"));
        verify(journal).record(eq("clem"), eq(AdminAction.ARM_BINDING), eq("binding:99"), any(), eq(AdminActionOutcome.REFUSED));
    }

    @Test
    @DisplayName("Plan : le déclenchement manuel est journalisé (PLAN) avec le résumé")
    void planIsJournaled() {
        OrderPlanService planService = mock(OrderPlanService.class);
        LocalDate day = LocalDate.of(2026, 10, 11);
        when(planService.planAll(day)).thenReturn(new PlanSummary(day, 2, 0, 0, 0, 0, 0));
        new AdminPlanTriggerService(planService, journal).plan(day, "clem");
        verify(journal).record(eq("clem"), eq(AdminAction.PLAN), eq("2026-10-11"), any(), eq(AdminActionOutcome.OK));

        when(planService.planAll(day)).thenThrow(new IllegalStateException("x"));
        assertThrows(IllegalStateException.class, () -> new AdminPlanTriggerService(planService, journal).plan(day, "clem"));
        verify(journal).record(eq("clem"), eq(AdminAction.PLAN), eq("2026-10-11"), any(), eq(AdminActionOutcome.ERROR));
    }

    @Test
    @DisplayName("Le job cron ExecutionJob n'est pas modifié : aucune dépendance au service admin ni au journal")
    void cronJobUntouched() {
        List<Class<?>> types = Arrays.stream(ExecutionJob.class.getDeclaredFields()).map(Field::getType).collect(Collectors.toList());
        assertTrue(types.contains(OrderExecutor.class));
        assertFalse(types.contains(AdminExecutionService.class) || types.contains(AdminActionLogService.class));
    }
}
