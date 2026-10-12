package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService.PlanSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** Déclenchement manuel du plan d'ordres dry-run (rien n'est envoyé), tracé au journal admin. */
@Service
@RequiredArgsConstructor
public class AdminPlanTriggerService {

    private final OrderPlanService planService;
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

    /** Remise à zéro d'un plan non envoyé (bloqué, expiré, désactivé…) : nouvelle révision, journal PLAN. */
    public PlanSummary reset(Long planId, String actor) {
        String target = "reset#" + planId;
        try {
            PlanSummary summary = planService.resetPlan(planId);
            journal.record(actor, AdminAction.PLAN, target, AdminActionLogService.payload("planned", summary.planned(),
                    "blocked", summary.blocked(), "disabled", summary.disabled(), "unchanged", summary.unchanged(),
                    "skipped", summary.skipped()), AdminActionOutcome.OK);
            return summary;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.PLAN, target, AdminActionLogService.payload("error", e.getClass().getSimpleName()),
                    e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }
}
