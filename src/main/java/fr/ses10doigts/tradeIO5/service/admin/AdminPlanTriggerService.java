package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService.PlanSummary;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** Déclenchement manuel du plan d'ordres dry-run (rien n'est envoyé), tracé au journal admin. */
@Service
@RequiredArgsConstructor
public class AdminPlanTriggerService {

    private final OrderPlanService planService;
    private final RainbowLiveExecutionService executionService;
    private final DomainClock clock;
    private final AdminActionLogService journal;

    public PlanSummary plan(LocalDate day, String actor) {
        String target = day.toString();
        try {
            PlanSummary summary = planService.planAll(day);
            journal.record(actor, AdminAction.PLAN, target, AdminActionLogService.payload("planned", summary.planned(),
                    "blocked", summary.blocked(), "disabled", summary.disabled(), "unchanged", summary.unchanged(),
                    "skipped", summary.skipped(), "errors", summary.errors()), AdminActionOutcome.OK);
            return summary;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.PLAN, target, AdminActionLogService.payload("error", e.getClass().getSimpleName()),
                    e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }

    /**
     * Remise à zéro d'un plan non envoyé (bloqué, expiré, désactivé…) : nouvelle révision, journal PLAN. Pour le jour UTC ouvert,
     * le signal Rainbow de l'utilisateur est d'abord recalculé à l'instant du reset (action figée et réservation de cash
     * levées) ; un jour clôturé garde son run.
     */
    public PlanSummary reset(Long planId, String actor) {
        return reset(planId, actor, null, null);
    }

    /**
     * Reset d'un lot : les séries D1 sont partagées via {@code batch} (nul : recalcul isolé, comportement unitaire) et le
     * {@code batchId} est ajouté au journal.
     */
    PlanSummary reset(Long planId, String actor, RainbowLiveExecutionService.RecomputeBatch batch, String batchId) {
        String target = "reset#" + planId;
        try {
            OrderPlanService.ResetTarget resetTarget = planService.resetTarget(planId);
            if (resetTarget.day().equals(RainbowLiveExecutionService.dayFor(batch == null ? clock.now() : batch.asOf()))) {
                if (batch == null) {
                    executionService.recomputeDay(resetTarget.user(), clock.now(), resetTarget.day());
                } else {
                    executionService.recomputeDay(resetTarget.user(), batch.asOf(), resetTarget.day(), batch);
                }
            }
            PlanSummary summary = planService.resetPlan(planId);
            journal.record(actor, AdminAction.PLAN, target, AdminActionLogService.payload("planned", summary.planned(),
                    "blocked", summary.blocked(), "disabled", summary.disabled(), "unchanged", summary.unchanged(),
                    "skipped", summary.skipped(), "batchId", batchId), AdminActionOutcome.OK);
            return summary;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.PLAN, target, AdminActionLogService.payload("error", e.getClass().getSimpleName(),
                    "batchId", batchId), e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }
}
