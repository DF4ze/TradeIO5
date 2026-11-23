package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static fr.ses10doigts.tradeIO5.service.execution.run.OrderEventLog.payload;

/**
 * Réconciliation automatique des plans restés {@code EXECUTING} : passes à +5, +10, +15 min ({@link ExecutionDefaults#RECONCILE_DELAYS}),
 * stoppées dès que plus aucun step n'est en vol. Lecture seule côté exchange ({@link OrderExecutor#reconcile} n'envoie jamais d'ordre,
 * ne dépend pas du kill switch). Toujours en vol après la dernière passe : audit {@code ALERT} + log ERROR ; le Docteur passe alors
 * le plan en CRIT. Une seule veille par plan ; non persistée (un redémarrage réconcilie via {@link ExecutionStartupReconciler}).
 */
@Slf4j
@Component
public class ExecutionReconcileWatcher {

    private final OrderExecutor executor;
    private final RainbowLiveOrderPlanRepository plans;
    private final OrderEventLog events;
    private final DomainClock clock;
    private final List<Duration> delays;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Set<Long> watched = ConcurrentHashMap.newKeySet();

    @Autowired
    public ExecutionReconcileWatcher(OrderExecutor executor, RainbowLiveOrderPlanRepository plans, OrderEventLog events, DomainClock clock) {
        this(executor, plans, events, clock, ExecutionDefaults.RECONCILE_DELAYS);
    }

    ExecutionReconcileWatcher(OrderExecutor executor, RainbowLiveOrderPlanRepository plans, OrderEventLog events, DomainClock clock,
                              List<Duration> delays) {
        this.executor = executor;
        this.plans = plans;
        this.events = events;
        this.clock = clock;
        this.delays = delays;
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("exec-reconcile-");
        scheduler.setDaemon(true);
        scheduler.initialize();
    }

    @EventListener
    public void onInFlight(PlanInFlightEvent event) {
        watch(event.planId());
    }

    /** Démarre la veille d'un plan (sans effet si déjà veillé). */
    public void watch(Long planId) {
        if (watched.add(planId)) {
            log.info("Plan {} en vol : réconciliation automatique dans {}", planId, delays.getFirst());
            schedule(planId, 0);
        }
    }

    private void schedule(Long planId, int pass) {
        Instant at = Instant.now().plus(delays.get(pass));
        scheduler.schedule(() -> run(planId, pass), at);
    }

    private void run(Long planId, int pass) {
        try {
            executor.reconcile(planId);
        } catch (RuntimeException e) {
            log.error("Réconciliation auto : échec plan {} (passe {})", planId, pass + 1, e);
        }
        boolean inFlight;
        try {
            inFlight = plans.findById(planId).map(RainbowLiveOrderPlan::getStatus).filter(s -> s == PlanStatus.EXECUTING).isPresent();
        } catch (RuntimeException e) {
            inFlight = true;
        }
        if (!inFlight) {
            log.info("Réconciliation auto : plan {} clos à la passe {}", planId, pass + 1);
            watched.remove(planId);
        } else if (pass + 1 < delays.size()) {
            log.warn("Réconciliation auto : plan {} toujours en vol après la passe {}, prochaine dans {}", planId, pass + 1, delays.get(pass + 1));
            schedule(planId, pass + 1);
        } else {
            log.error("Réconciliation auto : plan {} toujours en vol après {} passes : intervention manuelle requise", planId, delays.size());
            try {
                events.record(planId, null, null, OrderEventType.ALERT, payload("reason", "RECONCILE_EXHAUSTED", "passes", delays.size()));
            } catch (RuntimeException e) {
                log.warn("Réconciliation auto : audit impossible pour le plan {}", planId, e);
            }
            watched.remove(planId);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
