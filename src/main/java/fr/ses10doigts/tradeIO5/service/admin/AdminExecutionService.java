package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.run.ExecutionResult;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderExecutor;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pilotage admin de l'exécution : kill switch, armement du 1ᵉʳ ordre, run / réconciliation. Chaque action est tracée au
 * journal (auteur, cible, issue). <b>Réengagement automatique</b> : si {@code run} trouve le kill switch levé, il le
 * rengage dans un {@code finally} (succès, blocage ou exception) ; le job cron {@code ExecutionJob} n'est pas concerné.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminExecutionService {

    private final OrderExecutor executor;
    private final ExecutionControlService control;
    private final RainbowLiveBindingService bindings;
    private final RainbowLiveOrderPlanRepository planRepository;
    private final AdminActionLogService journal;

    public ExecutionControl setKillSwitch(boolean engaged, String actor) {
        String target = engaged ? "engage" : "lève";
        try {
            ExecutionControl saved = control.setKillSwitch(engaged, actor);
            journal.record(actor, AdminAction.KILL_SWITCH, target, AdminActionLogService.payload("engaged", engaged),
                    AdminActionOutcome.OK);
            return saved;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.KILL_SWITCH, target, AdminActionLogService.payload("engaged", engaged,
                    "error", e.getClass().getSimpleName()), AdminActionOutcome.ERROR);
            throw e;
        }
    }

    public void arm(Long bindingId, String actor) {
        arm(bindingId, actor, null);
    }

    /** Armement tracé ; {@code batchId} (nul hors lot) est ajouté au payload du journal. */
    void arm(Long bindingId, String actor, String batchId) {
        String target = "binding:" + bindingId;
        try {
            bindings.arm(bindingId);
            journal.record(actor, AdminAction.ARM_BINDING, target,
                    batchId == null ? null : AdminActionLogService.payload("batchId", batchId), AdminActionOutcome.OK);
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.ARM_BINDING, target, AdminActionLogService.payload("error",
                    e.getClass().getSimpleName(), "batchId", batchId), outcomeOf(e));
            throw e;
        }
    }

    /**
     * Exécute (plan {@code PLANNED}) ou réconcilie (plan {@code EXECUTING}) puis, si le kill switch était levé à l'appel,
     * le rengage quoi qu'il arrive.
     */
    public ExecutionResult run(Long planId, String actor) {
        boolean lifted = !control.current().isKillSwitch();
        try {
            return runWithoutReengage(planId, actor, null);
        } finally {
            if (lifted) {
                reengage("plan:" + planId, actor);
            }
        }
    }

    /**
     * Cœur de {@link #run} : exécute ou réconcilie et journalise, <b>sans rengager</b> le kill switch (le lot rengage une
     * seule fois à sa fin).
     */
    ExecutionResult runWithoutReengage(Long planId, String actor, String batchId) {
        boolean lifted = !control.current().isKillSwitch();
        AdminAction action = planRepository.findById(planId).map(p -> p.getStatus() == PlanStatus.EXECUTING)
                .orElse(false) ? AdminAction.RECONCILE : AdminAction.RUN;
        String target = "plan:" + planId;
        try {
            ExecutionResult result = executor.execute(planId);
            Map<String, String> blocked = new LinkedHashMap<>();
            result.blocked().forEach((asset, reason) -> blocked.put(asset, reason.name()));
            journal.record(actor, action, target, AdminActionLogService.payload("status", result.status(), "sent", result.sent(),
                    "blocked", blocked.isEmpty() ? null : blocked, "killSwitchWasLifted", lifted, "batchId", batchId),
                    result.sent() == 0 && !blocked.isEmpty() ? AdminActionOutcome.REFUSED : AdminActionOutcome.OK);
            return result;
        } catch (RuntimeException e) {
            journal.record(actor, action, target, AdminActionLogService.payload("error", e.getClass().getSimpleName(),
                    "killSwitchWasLifted", lifted, "batchId", batchId), outcomeOf(e));
            throw e;
        }
    }

    /** Rengagement unique de fin de lot. */
    void reengageBatch(String batchId, String triggeredBy) {
        reengage("batch:" + batchId, triggeredBy);
    }

    private void reengage(String target, String triggeredBy) {
        control.setKillSwitch(true, ExecutionDefaults.AUTO_REENGAGE_ACTOR);
        log.info("Kill switch rengagé automatiquement après le run de {}", target);
        journal.record(ExecutionDefaults.AUTO_REENGAGE_ACTOR, AdminAction.AUTO_REENGAGE, target,
                AdminActionLogService.payload("triggeredBy", triggeredBy), AdminActionOutcome.OK);
    }

    private static AdminActionOutcome outcomeOf(RuntimeException e) {
        return e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR;
    }
}
