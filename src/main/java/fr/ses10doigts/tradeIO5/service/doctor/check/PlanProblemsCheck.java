package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans d'ordres récents en difficulté (C4, A4) : {@code FAILED} (CRIT), {@code PARTIAL} / {@code EXPIRED} (WARN), plan resté
 * {@code EXECUTING} trop longtemps (CRIT), steps {@code UNKNOWN} (CRIT) ou {@code REJECTED} (WARN). Un verdict par plan courant
 * des {@link DoctorDefaults#PLAN_WINDOW_DAYS} derniers jours ; un plan remplacé ou sorti de la fenêtre résout son alarme.
 */
@Component
@RequiredArgsConstructor
public class PlanProblemsCheck implements DoctorCheck {

    private final RainbowLiveOrderPlanRepository plans;
    private final DomainClock clock;

    @Override
    public String id() {
        return "PLAN_PROBLEMS";
    }

    @Override
    public String label() {
        return "Plans d'ordres récents : échec, partiel, expiré, bloqué en exécution, step inconnu";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.EXECUTION;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CheckResult> run() {
        Instant now = clock.now();
        LocalDate today = RainbowLiveExecutionService.dayFor(now);
        List<CheckResult> results = new ArrayList<>();
        for (RainbowLiveOrderPlan plan : plans.findAdminPlans(PlanStatus.SUPERSEDED,
                today.minusDays(DoctorDefaults.PLAN_WINDOW_DAYS), today, null)) {
            CheckResult result = evaluate(plan, now);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    private CheckResult evaluate(RainbowLiveOrderPlan plan, Instant now) {
        DoctorStatus status = DoctorStatus.OK;
        List<String> problems = new ArrayList<>();
        switch (plan.getStatus()) {
            case FAILED -> {
                status = DoctorStatus.CRIT;
                problems.add("plan en échec" + reason(plan.getExecutionBlockReason()));
            }
            case PARTIAL -> {
                status = DoctorStatus.WARN;
                problems.add("plan partiel" + reason(plan.getExecutionBlockReason()));
            }
            case EXPIRED -> {
                status = DoctorStatus.WARN;
                problems.add("plan expiré");
            }
            case EXECUTING -> {
                Instant started = plan.getExecutionStartedAt();
                if (started != null && Duration.between(started, now).toMinutes() >= DoctorDefaults.PLAN_STUCK_MINUTES) {
                    status = DoctorStatus.CRIT;
                    problems.add("bloqué en EXECUTING depuis " + started);
                }
            }
            default -> {
                // PLANNED, BLOCKED (cas normal), DISABLED, EXECUTED : rien à signaler au niveau du plan
            }
        }
        for (RainbowLiveOrderStep step : plan.getSteps()) {
            if (step.getStatus() == StepStatus.UNKNOWN) {
                status = DoctorStatus.CRIT;
                problems.add("step #" + step.getRank() + " " + step.getAssetSymbol() + " état inconnu");
            } else if (step.getStatus() == StepStatus.REJECTED && status != DoctorStatus.CRIT) {
                status = DoctorStatus.WARN;
                problems.add("step #" + step.getRank() + " " + step.getAssetSymbol() + " rejeté");
            }
        }
        if (status == DoctorStatus.OK) {
            return null;
        }
        String subject = "plan#" + plan.getId() + " " + plan.getUser().getUsername() + " " + plan.getDay();
        return new CheckResult(subject, status, String.join(" ; ", problems));
    }

    private static String reason(String reason) {
        return reason == null || reason.isBlank() ? "" : " (" + reason + ")";
    }
}
