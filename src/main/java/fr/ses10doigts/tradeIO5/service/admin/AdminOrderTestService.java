package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Testeur d'ordre côté admin : délègue la validation à {@link OrderTestService} et trace chaque essai au journal
 * ({@link AdminAction#ORDER_VALIDATE}) avec un payload filtré (demande, verdict, code et message de l'exchange ; jamais de
 * clé, de signature ni de nonce, jamais la réponse brute).
 */
@Service
@RequiredArgsConstructor
public class AdminOrderTestService {

    private final OrderTestService orderTests;
    private final AdminActionLogService journal;

    public ValidationReportDto validate(OrderTestRequest request, String actor) {
        String target = request == null ? "order-test" : "user:" + request.userId() + "/" + request.provider() + "/" + request.instId();
        try {
            ValidationReportDto report = orderTests.validate(request);
            List<String> failed = report.localChecks().stream().filter(c -> !c.ok()).map(c -> c.name()).toList();
            journal.record(actor, AdminAction.ORDER_VALIDATE, target, AdminActionLogService.payload("side", request.side(),
                    "sz", report.normalizedRequest() == null ? null : report.normalizedRequest().sz(),
                    "px", report.normalizedRequest() == null ? null : report.normalizedRequest().px(),
                    "accepted", report.accepted(), "blockedBy", report.blockedBy(), "failedChecks", failed.isEmpty() ? null : failed,
                    "exchangeCode", report.exchangeCode(), "message", report.message()),
                    report.accepted() ? AdminActionOutcome.OK : AdminActionOutcome.REFUSED);
            return report;
        } catch (IllegalArgumentException e) {
            journal.record(actor, AdminAction.ORDER_VALIDATE, target, AdminActionLogService.payload("reason", e.getMessage()),
                    AdminActionOutcome.REFUSED);
            throw e;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.ORDER_VALIDATE, target, AdminActionLogService.payload("error", e.getClass().getSimpleName()),
                    AdminActionOutcome.ERROR);
            throw e;
        }
    }
}
