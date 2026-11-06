package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlanAsset;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService.RecomputeBatch;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService.ResetCheck;
import fr.ses10doigts.tradeIO5.service.execution.run.ExecutionResult;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Actions groupées de la Console admin sur des plans (reset, armement, exécution, réconciliation). Lot <b>synchrone</b>,
 * séquentiel, taille bornée ({@value ExecutionDefaults#BULK_MAX_PLANS} par défaut, propriété
 * {@value ExecutionDefaults#BULK_MAX_PLANS_PROPERTY}), un seul lot à la fois. L'éligibilité est calculée ICI, par une méthode
 * unique partagée par la prévisualisation ({@code dryRun}) et l'exécution réelle. Chaque plan délègue aux services unitaires
 * (journal par plan conservé, {@code batchId} ajouté). Le kill switch n'est jamais levé par le lot ; s'il l'était à l'entrée,
 * il est rengagé UNE fois à la fin (succès, blocage ou exception).
 */
@Slf4j
@Service
public class AdminBulkPlanService {

    public enum BulkAction { RESET, ARM, RUN, RECONCILE }

    public enum BulkSkipReason {
        NOT_FOUND, NOT_RESETTABLE, NOT_ACTIVE, NOTHING_TO_ARM, NOT_PLANNED, NOT_ARMED, NOT_CONFIRMED, KILL_SWITCH_ENGAGED,
        NOT_IN_FLIGHT, SUPERSEDED
    }

    public enum BulkOutcome { DONE, SKIPPED, FAILED, ELIGIBLE }

    /** Issue d'un plan ; {@code orders} et {@code quoteTotal} : récap RUN en {@code dryRun} uniquement. */
    public record BulkItem(Long planId, Long userId, String username, LocalDate day, BulkOutcome outcome, BulkSkipReason reason,
                           String detail, long durationMs, Integer orders, BigDecimal quoteTotal) {
    }

    public record BulkResult(String batchId, BulkAction action, boolean dryRun, int requested, int done, int skipped, int failed,
                             long durationMs, List<BulkItem> items) {
    }

    /** Photo d'un plan lue en base (sans entité attachée) : alimente la règle d'éligibilité. */
    private record Facts(Long planId, Long userId, String username, LocalDate day, PlanStatus status, PlanStatus effective,
                         List<Long> unarmedBindingIds, boolean allArmed, boolean allConfirmed, boolean anyInFlight, int orders,
                         BigDecimal quoteTotal) {
    }

    private record Verdict(Facts facts, BulkSkipReason reason) {
        boolean eligible() {
            return reason == null;
        }
    }

    private static final int DETAIL_MAX = 200;

    private final AdminExecutionService executionService;
    private final AdminPlanTriggerService triggerService;
    private final OrderPlanService planService;
    private final RainbowLiveOrderPlanRepository planRepository;
    private final RainbowLiveBindingRepository bindingRepository;
    private final ExecutionControlService control;
    private final RainbowLiveExecutionService liveService;
    private final AdminActionLogService journal;
    private final DomainClock clock;
    private final TransactionTemplate readTx;
    private final int maxPlans;
    private final ReentrantLock lock = new ReentrantLock();

    public AdminBulkPlanService(AdminExecutionService executionService, AdminPlanTriggerService triggerService,
                                OrderPlanService planService, RainbowLiveOrderPlanRepository planRepository,
                                RainbowLiveBindingRepository bindingRepository, ExecutionControlService control,
                                RainbowLiveExecutionService liveService, AdminActionLogService journal, DomainClock clock,
                                PlatformTransactionManager transactionManager,
                                @Value("${" + ExecutionDefaults.BULK_MAX_PLANS_PROPERTY + ":" + ExecutionDefaults.BULK_MAX_PLANS + "}") int maxPlans) {
        this.executionService = executionService;
        this.triggerService = triggerService;
        this.planService = planService;
        this.planRepository = planRepository;
        this.bindingRepository = bindingRepository;
        this.control = control;
        this.liveService = liveService;
        this.journal = journal;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.maxPlans = maxPlans;
    }

    /**
     * @param confirm requis (= {@value ExecutionDefaults#BULK_RUN_CONFIRM}) pour un {@code RUN} réel
     * @throws IllegalArgumentException liste vide / doublons / trop grande, confirmation manquante, lot déjà en cours
     */
    public BulkResult execute(BulkAction action, List<Long> planIds, boolean dryRun, String confirm, String actor) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        long start = System.nanoTime();
        if (dryRun) {
            validate(action, planIds, true, confirm);
            return result(batchId, action, true, planIds, previews(action, planIds), start);
        }
        try {
            validate(action, planIds, false, confirm);
        } catch (IllegalArgumentException e) {
            refused(actor, action, batchId, e.getMessage());
            throw e;
        }
        if (!lock.tryLock()) {
            refused(actor, action, batchId, "lot déjà en cours");
            throw new IllegalArgumentException("Lot déjà en cours");
        }
        try {
            log.info("Lot {} {} : {} plan(s) par {}", batchId, action, planIds.size(), actor);
            BulkResult result = run(action, planIds, actor, batchId, start);
            journal.record(actor, AdminAction.BULK, "batch:" + batchId, AdminActionLogService.payload("action", action,
                    "requested", result.requested(), "done", result.done(), "skipped", result.skipped(), "failed", result.failed(),
                    "durationMs", result.durationMs()), result.failed() > 0 ? AdminActionOutcome.ERROR : AdminActionOutcome.OK);
            log.info("Lot {} {} terminé : fait={} sauté={} échec={} en {} ms", batchId, action, result.done(), result.skipped(),
                    result.failed(), result.durationMs());
            return result;
        } finally {
            lock.unlock();
        }
    }

    private void validate(BulkAction action, List<Long> planIds, boolean dryRun, String confirm) {
        if (planIds == null || planIds.isEmpty()) {
            throw new IllegalArgumentException("Aucun plan sélectionné");
        }
        if (new HashSet<>(planIds).size() != planIds.size()) {
            throw new IllegalArgumentException("Identifiants de plan en double");
        }
        if (planIds.size() > maxPlans) {
            throw new IllegalArgumentException("Lot trop grand : " + planIds.size() + " plans (maximum " + maxPlans + ")");
        }
        if (action == BulkAction.RUN && !dryRun && !ExecutionDefaults.BULK_RUN_CONFIRM.equals(confirm)) {
            throw new IllegalArgumentException("Confirmation requise : saisir " + ExecutionDefaults.BULK_RUN_CONFIRM);
        }
    }

    private void refused(String actor, BulkAction action, String batchId, String error) {
        journal.record(actor, AdminAction.BULK, "batch:" + batchId, AdminActionLogService.payload("action", action, "error", error),
                AdminActionOutcome.REFUSED);
    }

    private BulkResult result(String batchId, BulkAction action, boolean dryRun, List<Long> planIds, List<BulkItem> items, long start) {
        int done = count(items, BulkOutcome.DONE);
        int skipped = count(items, BulkOutcome.SKIPPED);
        int failed = count(items, BulkOutcome.FAILED);
        return new BulkResult(batchId, action, dryRun, planIds.size(), done, skipped, failed, ms(start), items);
    }

    private static int count(List<BulkItem> items, BulkOutcome outcome) {
        return (int) items.stream().filter(i -> i.outcome() == outcome).count();
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Prévisualisation : aucune mutation, même règle d'éligibilité que l'exécution. */
    private List<BulkItem> previews(BulkAction action, List<Long> planIds) {
        boolean killSwitch = control.current().isKillSwitch();
        List<BulkItem> items = new ArrayList<>();
        for (Long planId : planIds) {
            long t = System.nanoTime();
            Verdict v = verdict(action, planId, killSwitch);
            Facts f = v.facts();
            boolean withTotals = action == BulkAction.RUN && v.eligible();
            items.add(item(planId, f, v.eligible() ? BulkOutcome.ELIGIBLE : BulkOutcome.SKIPPED, v.reason(), null, t,
                    withTotals ? f.orders() : null, withTotals ? f.quoteTotal() : null));
        }
        return items;
    }

    private BulkResult run(BulkAction action, List<Long> planIds, String actor, String batchId, long start) {
        boolean runLike = action == BulkAction.RUN || action == BulkAction.RECONCILE;
        boolean lifted = runLike && !control.current().isKillSwitch();
        RecomputeBatch recompute = action == BulkAction.RESET ? liveService.newRecomputeBatch(clock.now()) : null;
        List<BulkItem> items = new ArrayList<>();
        try {
            for (Long planId : planIds) {
                long t = System.nanoTime();
                Verdict v = verdict(action, planId, runLike && !lifted);
                if (!v.eligible()) {
                    items.add(item(planId, v.facts(), BulkOutcome.SKIPPED, v.reason(), null, t, null, null));
                    continue;
                }
                items.add(apply(action, v.facts(), actor, batchId, recompute, t));
            }
        } finally {
            if (lifted) {
                executionService.reengageBatch(batchId, actor);
            }
        }
        return result(batchId, action, false, planIds, items, start);
    }

    private BulkItem apply(BulkAction action, Facts f, String actor, String batchId, RecomputeBatch recompute, long t) {
        try {
            switch (action) {
                case RESET -> triggerService.reset(f.planId(), actor, recompute, batchId);
                case ARM -> f.unarmedBindingIds().forEach(id -> executionService.arm(id, actor, batchId));
                case RUN, RECONCILE -> {
                    ExecutionResult r = executionService.runWithoutReengage(f.planId(), actor, batchId);
                    if (r.sent() == 0 && !r.blocked().isEmpty()) {
                        return item(f.planId(), f, BulkOutcome.FAILED, null, "bloqué : " + blockedOf(r), t, null, null);
                    }
                    return item(f.planId(), f, BulkOutcome.DONE, null, r.status() + ", " + r.sent() + " ordre(s) envoyé(s)"
                            + (r.blocked().isEmpty() ? "" : ", bloqué : " + blockedOf(r)), t, null, null);
                }
            }
            return item(f.planId(), f, BulkOutcome.DONE, null, null, t, null, null);
        } catch (RuntimeException e) {
            log.warn("Lot {} {} : échec du plan {} : {}", batchId, action, f.planId(), e.getClass().getSimpleName());
            return item(f.planId(), f, BulkOutcome.FAILED, null, cut(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " : " + e.getMessage())), t, null, null);
        }
    }

    private static String blockedOf(ExecutionResult r) {
        Map<String, String> blocked = new LinkedHashMap<>();
        r.blocked().forEach((asset, reason) -> blocked.put(asset, reason.name()));
        return blocked.toString();
    }

    private static String cut(String text) {
        return text.length() <= DETAIL_MAX ? text : text.substring(0, DETAIL_MAX);
    }

    private static BulkItem item(Long planId, Facts f, BulkOutcome outcome, BulkSkipReason reason, String detail, long startNanos,
                                 Integer orders, BigDecimal quoteTotal) {
        return new BulkItem(planId, f == null ? null : f.userId(), f == null ? null : f.username(), f == null ? null : f.day(),
                outcome, reason, detail, ms(startNanos), orders, quoteTotal);
    }

    // ------------------------------------------------------------------ éligibilité (source unique)

    private Verdict verdict(BulkAction action, Long planId, boolean killSwitchEngaged) {
        Facts facts = readTx.execute(status -> factsOf(planId));
        if (facts == null) {
            return new Verdict(null, BulkSkipReason.NOT_FOUND);
        }
        if (action == BulkAction.RESET) {
            ResetCheck check = planService.resetCheck(planId);
            BulkSkipReason reason = switch (check) {
                case OK -> null;
                case NOT_FOUND -> BulkSkipReason.NOT_FOUND;
                case SUPERSEDED -> BulkSkipReason.SUPERSEDED;
                case NOT_RESETTABLE -> BulkSkipReason.NOT_RESETTABLE;
            };
            return new Verdict(facts, reason);
        }
        return new Verdict(facts, reasonOf(action, facts, killSwitchEngaged));
    }

    private static BulkSkipReason reasonOf(BulkAction action, Facts f, boolean killSwitchEngaged) {
        if (f.status() == PlanStatus.SUPERSEDED) {
            return BulkSkipReason.SUPERSEDED;
        }
        return switch (action) {
            case ARM -> {
                if (f.status() != PlanStatus.PLANNED && f.status() != PlanStatus.EXECUTING) {
                    yield BulkSkipReason.NOT_ACTIVE;
                }
                yield f.unarmedBindingIds().isEmpty() ? BulkSkipReason.NOTHING_TO_ARM : null;
            }
            case RUN -> {
                if (killSwitchEngaged) {
                    yield BulkSkipReason.KILL_SWITCH_ENGAGED;
                }
                if (f.effective() != PlanStatus.PLANNED) {
                    yield BulkSkipReason.NOT_PLANNED;
                }
                if (!f.allArmed()) {
                    yield BulkSkipReason.NOT_ARMED;
                }
                yield f.allConfirmed() ? null : BulkSkipReason.NOT_CONFIRMED;
            }
            case RECONCILE -> {
                if (killSwitchEngaged) {
                    yield BulkSkipReason.KILL_SWITCH_ENGAGED;
                }
                yield f.status() == PlanStatus.EXECUTING || f.anyInFlight() ? null : BulkSkipReason.NOT_IN_FLIGHT;
            }
            case RESET -> null; // décidé par OrderPlanService#resetCheck
        };
    }

    /** Lecture du plan et de ses liens (dans une transaction : steps / assets sont paresseux) ; {@code null} si inconnu. */
    private Facts factsOf(Long planId) {
        Optional<RainbowLiveOrderPlan> found = planRepository.findById(planId);
        if (found.isEmpty()) {
            return null;
        }
        RainbowLiveOrderPlan plan = found.get();
        Set<String> simulated = new HashSet<>();
        List<Long> unarmed = new ArrayList<>();
        boolean allArmed = true;
        boolean allConfirmed = true;
        for (RainbowLiveOrderPlanAsset asset : plan.getAssets()) {
            boolean hasSteps = plan.getSteps().stream().anyMatch(s -> s.getAssetSymbol().equals(asset.getAssetSymbol())
                    && (s.getWalletId() == null || s.getWalletId().equals(asset.getWalletId())));
            if (asset.isSimulated()) {
                simulated.add(asset.getAssetSymbol());
            }
            if (!hasSteps || asset.isSimulated()) {
                continue;
            }
            Optional<RainbowLiveBinding> binding = bindingRepository.findByUserAndAssetSymbolAndWallet_Id(plan.getUser(),
                    asset.getAssetSymbol(), asset.getWalletId()).filter(b -> !b.isArchived());
            if (binding.isEmpty()) {
                continue; // l'exécuteur bloque NO_BINDING : pas un sujet d'armement
            }
            if (binding.get().getFirstLiveApprovedAt() == null) {
                allArmed = false;
                unarmed.add(binding.get().getId());
            } else if (binding.get().getFirstLiveConfirmedAt() == null) {
                allConfirmed = false;
            }
        }
        int orders = 0;
        BigDecimal quoteTotal = BigDecimal.ZERO;
        boolean inFlight = false;
        for (RainbowLiveOrderStep step : plan.getSteps()) {
            inFlight |= step.getStatus().inFlight();
            if (simulated.contains(step.getAssetSymbol())) {
                continue;
            }
            orders++;
            if (step.getInstId() != null && step.getInstId().endsWith("-" + ExecutionDefaults.CASH_STABLE)
                    && step.getQuoteAmount() != null) {
                quoteTotal = quoteTotal.add(step.getQuoteAmount());
            }
        }
        return new Facts(planId, plan.getUser().getId(), plan.getUser().getUsername(), plan.getDay(), plan.getStatus(),
                plan.effectiveStatus(clock.now()), unarmed, allArmed, allConfirmed, inFlight, orders, quoteTotal);
    }
}
