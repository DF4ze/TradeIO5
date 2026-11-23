package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExecutionReconcileWatcher")
class ExecutionReconcileWatcherTest {

    private static final List<Duration> DELAYS = List.of(Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(20));

    private final OrderExecutor executor = mock(OrderExecutor.class);
    private final RainbowLiveOrderPlanRepository plans = mock(RainbowLiveOrderPlanRepository.class);
    private final OrderEventLog events = mock(OrderEventLog.class);
    private final ExecutionReconcileWatcher watcher =
            new ExecutionReconcileWatcher(executor, plans, events, new FixedDomainClock(Instant.EPOCH), DELAYS);

    private void planStatus(PlanStatus first, PlanStatus... next) {
        RainbowLiveOrderPlan[] more = new RainbowLiveOrderPlan[next.length];
        for (int i = 0; i < next.length; i++) {
            more[i] = RainbowLiveOrderPlan.builder().id(7L).status(next[i]).build();
        }
        var stub = when(plans.findById(7L)).thenReturn(Optional.of(RainbowLiveOrderPlan.builder().id(7L).status(first).build()));
        for (RainbowLiveOrderPlan p : more) {
            stub = stub.thenReturn(Optional.of(p));
        }
    }

    @Test
    @DisplayName("Clos dès la 1re passe : une seule réconciliation, aucune alerte")
    void closedAtFirstPass() {
        planStatus(PlanStatus.EXECUTED);
        watcher.watch(7L);
        verify(executor, timeout(2000)).reconcile(7L);
        verify(executor, after(300).times(1)).reconcile(7L);
        verify(events, never()).record(any(), any(), any(), eq(OrderEventType.ALERT), any());
    }

    @Test
    @DisplayName("Clos à la 2e passe : 2 réconciliations, aucune alerte")
    void closedAtSecondPass() {
        planStatus(PlanStatus.EXECUTING, PlanStatus.EXECUTED);
        watcher.watch(7L);
        verify(executor, timeout(2000).times(2)).reconcile(7L);
        verify(executor, after(300).times(2)).reconcile(7L);
        verify(events, never()).record(any(), any(), any(), eq(OrderEventType.ALERT), any());
    }

    @Test
    @DisplayName("Toujours en vol après 3 passes : alerte d'audit RECONCILE_EXHAUSTED, puis plus rien")
    void exhausted() {
        planStatus(PlanStatus.EXECUTING);
        watcher.watch(7L);
        verify(events, timeout(2000)).record(eq(7L), isNull(), isNull(), eq(OrderEventType.ALERT), any());
        verify(executor, after(300).times(3)).reconcile(7L);
    }

    @Test
    @DisplayName("Une seule veille par plan")
    void singleWatch() {
        planStatus(PlanStatus.EXECUTED);
        watcher.watch(7L);
        watcher.watch(7L);
        verify(executor, timeout(2000).times(1)).reconcile(7L);
        verify(executor, after(300).times(1)).reconcile(7L);
    }
}
