package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService.Limits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Modification des plafonds de dépense et des seuils Fee Test, tracée au journal admin (valeurs avant/après, aucun secret). */
@Service
@RequiredArgsConstructor
public class AdminLimitsService {

    public static final String TARGET = "execution-limits";

    private final ExecutionControlService control;
    private final AdminActionLogService journal;

    public ExecutionControl update(Limits limits, String actor) {
        Limits before = Limits.of(control.current());
        try {
            ExecutionControl saved = control.updateLimits(limits, actor);
            journal.record(actor, AdminAction.LIMITS, TARGET,
                    AdminActionLogService.payload("before", view(before), "after", view(Limits.of(saved))), AdminActionOutcome.OK);
            return saved;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.LIMITS, TARGET, AdminActionLogService.payload("error", e.getClass().getSimpleName()),
                    e instanceof IllegalArgumentException ? AdminActionOutcome.REFUSED : AdminActionOutcome.ERROR);
            throw e;
        }
    }

    private static Map<String, Object> view(Limits l) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("maxOrderMultiplier", l.maxOrderMultiplier().doubleValue());
        view.put("maxDayMultiplier", l.maxDayMultiplier().doubleValue());
        view.put("absoluteMaxOrderUsd", l.absoluteMaxOrderUsd().doubleValue());
        view.put("absoluteMaxDayUsd", l.absoluteMaxDayUsd().doubleValue());
        view.put("feeWarnPct", l.feeWarnPct().doubleValue());
        view.put("feeRedPct", l.feeRedPct().doubleValue());
        return view;
    }
}
