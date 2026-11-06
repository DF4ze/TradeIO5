package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlanAsset;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService.BulkAction;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService.BulkOutcome;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService.BulkResult;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService.BulkSkipReason;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService.ResetCheck;
import fr.ses10doigts.tradeIO5.service.execution.run.ExecutionBlockReason;
import fr.ses10doigts.tradeIO5.service.execution.run.ExecutionResult;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Console admin : actions groupées sur les plans")
class AdminBulkPlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    private AdminExecutionService execution;
    private AdminPlanTriggerService trigger;
    private OrderPlanService planService;
    private RainbowLiveOrderPlanRepository plans;
    private RainbowLiveBindingRepository bindings;
    private ExecutionControlService control;
    private RainbowLiveExecutionService live;
    private AdminActionLogService journal;
    private AdminBulkPlanService service;
    private final User user = User.builder().id(1L).username("alice").build();

    @BeforeEach
    void setUp() {
        execution = mock(AdminExecutionService.class);
        trigger = mock(AdminPlanTriggerService.class);
        planService = mock(OrderPlanService.class);
        plans = mock(RainbowLiveOrderPlanRepository.class);
        bindings = mock(RainbowLiveBindingRepository.class);
        control = mock(ExecutionControlService.class);
        live = mock(RainbowLiveExecutionService.class);
        journal = mock(AdminActionLogService.class);
        DomainClock clock = mock(DomainClock.class);
        when(clock.now()).thenReturn(NOW);
        service = new AdminBulkPlanService(execution, trigger, planService, plans, bindings, control, live, journal, clock,
                mock(PlatformTransactionManager.class), 3);
        killSwitch(false);
    }

    private void killSwitch(boolean engaged) {
        ExecutionControl c = ExecutionControl.initial(Instant.EPOCH);
        c.setKillSwitch(engaged);
        when(control.current()).thenReturn(c);
    }

    /** Plan d'un actif BTC/wallet 5 avec une étape USDC de 100. */
    private RainbowLiveOrderPlan plan(long id, PlanStatus status, StepStatus stepStatus) {
        RainbowLiveOrderPlan p = RainbowLiveOrderPlan.builder().id(id).user(user).day(DAY).revision(1).status(status)
                .expiresAt(NOW.plusSeconds(300)).assets(new ArrayList<>()).steps(new ArrayList<>()).build();
        p.getAssets().add(RainbowLiveOrderPlanAsset.builder().assetSymbol("BTC").walletId(5L).build());
        p.getSteps().add(RainbowLiveOrderStep.builder().rank(1).assetSymbol("BTC").walletId(5L).instId("BTC-USDC")
                .quoteAmount(new BigDecimal("100")).status(stepStatus).build());
        when(plans.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private RainbowLiveOrderPlan plan(long id, PlanStatus status) {
        return plan(id, status, StepStatus.PLANNED);
    }

    private void binding(long id, boolean armed, boolean confirmed) {
        RainbowLiveBinding b = RainbowLiveBinding.builder().id(id).assetSymbol("BTC")
                .firstLiveApprovedAt(armed ? NOW : null).firstLiveConfirmedAt(confirmed ? NOW : null).build();
        when(bindings.findByUserAndAssetSymbolAndWallet_Id(any(), eq("BTC"), eq(5L))).thenReturn(Optional.of(b));
    }

    private BulkResult dry(BulkAction action, Long... ids) {
        return service.execute(action, List.of(ids), true, null, "clem");
    }

    private static BulkSkipReason reasonOf(BulkResult r, int index) {
        return r.items().get(index).reason();
    }

    @Test
    @DisplayName("Éligibilité RUN : chaque motif, plan inconnu et remplacé compris")
    void runEligibility() {
        plan(1, PlanStatus.PLANNED);
        binding(10, true, true);
        assertEquals(BulkOutcome.ELIGIBLE, dry(BulkAction.RUN, 1L).items().getFirst().outcome());

        binding(10, false, false);
        assertEquals(BulkSkipReason.NOT_ARMED, reasonOf(dry(BulkAction.RUN, 1L), 0));
        binding(10, true, false);
        assertEquals(BulkSkipReason.NOT_CONFIRMED, reasonOf(dry(BulkAction.RUN, 1L), 0));

        plan(2, PlanStatus.EXECUTED);
        assertEquals(BulkSkipReason.NOT_PLANNED, reasonOf(dry(BulkAction.RUN, 2L), 0));
        plan(3, PlanStatus.SUPERSEDED);
        assertEquals(BulkSkipReason.SUPERSEDED, reasonOf(dry(BulkAction.RUN, 3L), 0));
        when(plans.findById(4L)).thenReturn(Optional.empty());
        assertEquals(BulkSkipReason.NOT_FOUND, reasonOf(dry(BulkAction.RUN, 4L), 0));
        RainbowLiveOrderPlan expired = plan(5, PlanStatus.PLANNED);
        expired.setExpiresAt(NOW.minusSeconds(1));
        assertEquals(BulkSkipReason.NOT_PLANNED, reasonOf(dry(BulkAction.RUN, 5L), 0));
    }

    @Test
    @DisplayName("Éligibilité ARM, RESET et RECONCILE")
    void otherEligibilities() {
        plan(1, PlanStatus.PLANNED);
        binding(10, false, false);
        BulkResult arm = dry(BulkAction.ARM, 1L);
        assertEquals(BulkOutcome.ELIGIBLE, arm.items().getFirst().outcome());
        binding(10, true, false);
        assertEquals(BulkSkipReason.NOTHING_TO_ARM, reasonOf(dry(BulkAction.ARM, 1L), 0));
        plan(2, PlanStatus.BLOCKED);
        assertEquals(BulkSkipReason.NOT_ACTIVE, reasonOf(dry(BulkAction.ARM, 2L), 0));

        when(planService.resetCheck(1L)).thenReturn(ResetCheck.OK);
        when(planService.resetCheck(2L)).thenReturn(ResetCheck.NOT_RESETTABLE);
        when(planService.resetCheck(3L)).thenReturn(ResetCheck.SUPERSEDED);
        plan(3, PlanStatus.SUPERSEDED);
        BulkResult reset = dry(BulkAction.RESET, 1L, 2L, 3L);
        assertEquals(BulkOutcome.ELIGIBLE, reset.items().get(0).outcome());
        assertEquals(BulkSkipReason.NOT_RESETTABLE, reasonOf(reset, 1));
        assertEquals(BulkSkipReason.SUPERSEDED, reasonOf(reset, 2));

        plan(6, PlanStatus.PARTIAL, StepStatus.UNKNOWN);
        assertEquals(BulkOutcome.ELIGIBLE, dry(BulkAction.RECONCILE, 6L).items().getFirst().outcome());
        plan(7, PlanStatus.EXECUTED, StepStatus.FILLED);
        assertEquals(BulkSkipReason.NOT_IN_FLIGHT, reasonOf(dry(BulkAction.RECONCILE, 7L), 0));
    }

    @Test
    @DisplayName("dryRun : aucune mutation, récap RUN (ordres, total USDC), pas de journal")
    void dryRunDoesNotMutate() {
        plan(1, PlanStatus.PLANNED);
        binding(10, true, true);
        BulkResult r = dry(BulkAction.RUN, 1L);
        assertTrue(r.dryRun());
        assertEquals(1, r.items().getFirst().orders());
        assertEquals(0, new BigDecimal("100").compareTo(r.items().getFirst().quoteTotal()));
        verifyNoInteractions(trigger, journal, live);
        verify(execution, never()).runWithoutReengage(anyLong(), anyString(), any());
        verify(execution, never()).reengageBatch(anyString(), anyString());
    }

    @Test
    @DisplayName("Validation : vide, doublons, taille max, confirmation RUN ; refus journalisé")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(BulkAction.RESET, List.of(), false, null, "clem"));
        assertThrows(IllegalArgumentException.class, () -> service.execute(BulkAction.RESET, List.of(1L, 1L), false, null, "clem"));
        assertThrows(IllegalArgumentException.class, () -> service.execute(BulkAction.RESET, List.of(1L, 2L, 3L, 4L), false, null, "clem"));
        assertThrows(IllegalArgumentException.class, () -> service.execute(BulkAction.RUN, List.of(1L), false, "oui", "clem"));
        assertThrows(IllegalArgumentException.class, () -> service.execute(BulkAction.RUN, List.of(1L), false, null, "clem"));
        verify(journal, times(5)).record(eq("clem"), eq(AdminAction.BULK), anyString(), any(), eq(AdminActionOutcome.REFUSED));
        verifyNoInteractions(trigger);
    }

    @Test
    @DisplayName("Lot partiel : un FAILED n'arrête pas le lot ; 1 ligne BULK, batchId transmis aux services unitaires")
    void partialBatchAndJournal() {
        plan(1, PlanStatus.BLOCKED);
        plan(2, PlanStatus.BLOCKED);
        when(planService.resetCheck(anyLong())).thenReturn(ResetCheck.OK);
        when(live.newRecomputeBatch(NOW)).thenReturn(null);
        when(trigger.reset(eq(1L), eq("clem"), any(), anyString())).thenThrow(new IllegalStateException("boom"));

        BulkResult r = service.execute(BulkAction.RESET, List.of(1L, 2L), false, null, "clem");

        assertEquals(BulkOutcome.FAILED, r.items().get(0).outcome());
        assertEquals(BulkOutcome.DONE, r.items().get(1).outcome());
        assertEquals(1, r.done());
        assertEquals(1, r.failed());
        verify(trigger).reset(eq(2L), eq("clem"), any(), eq(r.batchId()));
        verify(journal).record(eq("clem"), eq(AdminAction.BULK), eq("batch:" + r.batchId()), any(), eq(AdminActionOutcome.ERROR));
    }

    @Test
    @DisplayName("ARM : arme les liens non armés avec le batchId")
    void armDelegates() {
        plan(1, PlanStatus.PLANNED);
        binding(10, false, false);
        BulkResult r = service.execute(BulkAction.ARM, List.of(1L), false, null, "clem");
        verify(execution).arm(10L, "clem", r.batchId());
        assertEquals(1, r.done());
    }

    @Test
    @DisplayName("Kill switch engagé au départ : tous les RUN sautés, jamais levé ni rengagé par le lot")
    void killSwitchEngagedSkipsAll() {
        killSwitch(true);
        plan(1, PlanStatus.PLANNED);
        plan(2, PlanStatus.PLANNED);
        binding(10, true, true);
        BulkResult r = service.execute(BulkAction.RUN, List.of(1L, 2L), false, "EXECUTER", "clem");
        assertEquals(2, r.skipped());
        assertEquals(BulkSkipReason.KILL_SWITCH_ENGAGED, reasonOf(r, 0));
        verify(execution, never()).runWithoutReengage(anyLong(), anyString(), any());
        verify(execution, never()).reengageBatch(anyString(), anyString());
    }

    @Test
    @DisplayName("Kill switch levé : rengagement UNIQUE en fin de lot (succès, blocage et exception)")
    void singleReengage() {
        plan(1, PlanStatus.PLANNED);
        plan(2, PlanStatus.PLANNED);
        plan(3, PlanStatus.PLANNED);
        binding(10, true, true);
        when(execution.runWithoutReengage(eq(1L), anyString(), anyString()))
                .thenReturn(new ExecutionResult(1L, PlanStatus.EXECUTED, Map.of(), 1));
        when(execution.runWithoutReengage(eq(2L), anyString(), anyString()))
                .thenReturn(new ExecutionResult(2L, PlanStatus.BLOCKED, Map.of("BTC", ExecutionBlockReason.CAP_ORDER), 0));
        when(execution.runWithoutReengage(eq(3L), anyString(), anyString())).thenThrow(new IllegalStateException("x"));

        BulkResult r = service.execute(BulkAction.RUN, List.of(1L, 2L, 3L), false, "EXECUTER", "clem");

        assertEquals(BulkOutcome.DONE, r.items().get(0).outcome());
        assertEquals(BulkOutcome.FAILED, r.items().get(1).outcome());
        assertEquals(BulkOutcome.FAILED, r.items().get(2).outcome());
        verify(execution, times(1)).reengageBatch(r.batchId(), "clem");
    }

    @Test
    @DisplayName("Lot concurrent refusé (verrou non bloquant)")
    void concurrentBatchRefused() throws Exception {
        plan(1, PlanStatus.BLOCKED);
        when(planService.resetCheck(anyLong())).thenReturn(ResetCheck.OK);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(trigger.reset(anyLong(), anyString(), any(), anyString())).thenAnswer(inv -> {
            inside.countDown();
            release.await();
            return null;
        });
        Thread t = new Thread(() -> service.execute(BulkAction.RESET, List.of(1L), false, null, "clem"));
        t.start();
        assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.execute(BulkAction.RESET, List.of(1L), false, null, "clem"));
            assertTrue(e.getMessage().contains("déjà en cours"));
        } finally {
            release.countDown();
            t.join();
        }
        assertFalse(t.isAlive());
        verify(trigger, times(1)).reset(anyLong(), anyString(), any(), anyString());
    }
}
