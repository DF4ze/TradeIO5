package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.RealOrderRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.RealOrderResultDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.ReconcileRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderTestService;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Testeur d'ordre côté admin : délègue la validation à {@link OrderTestService} et trace chaque essai au journal
 * ({@link AdminAction#ORDER_VALIDATE}) avec un payload filtré (demande, verdict, code et message de l'exchange ; jamais de
 * clé, de signature ni de nonce, jamais la réponse brute).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderTestService {

    private final OrderTestService orderTests;
    private final AdminActionLogService journal;
    private final ExecutionControlService control;

    /**
     * Envoi d'un ordre RÉEL (confirmation exacte requise), tracé au journal ({@link AdminAction#ORDER_SEND}). Si le kill switch
     * était levé à l'appel, il est rengagé quoi qu'il arrive (succès, blocage ou exception), comme après un {@code run}.
     */
    public RealOrderResultDto sendReal(RealOrderRequest request, String actor) {
        OrderTestRequest order = request == null ? null : request.order();
        String target = order == null ? "order-send" : "user:" + order.userId() + "/" + order.provider() + "/" + order.instId();
        boolean wasLifted = !control.current().isKillSwitch();
        try {
            RealOrderResultDto result = orderTests.sendReal(order, request == null ? null : request.confirm());
            journal.record(actor, AdminAction.ORDER_SEND, target, AdminActionLogService.payload("side", order.side(),
                    "status", result.status(), "clOrdId", result.clOrdId(), "ordId", result.ordId(), "phase", result.phase(),
                    "filledSz", result.filledSz(), "avgPx", result.avgPx(), "message", result.message()),
                    "SENT".equals(result.status()) ? AdminActionOutcome.OK : AdminActionOutcome.REFUSED);
            return result;
        } catch (IllegalArgumentException e) {
            journal.record(actor, AdminAction.ORDER_SEND, target, AdminActionLogService.payload("reason", e.getMessage()),
                    AdminActionOutcome.REFUSED);
            throw e;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.ORDER_SEND, target, AdminActionLogService.payload("error", e.getClass().getSimpleName()),
                    AdminActionOutcome.ERROR);
            throw e;
        } finally {
            if (wasLifted) {
                control.setKillSwitch(true, ExecutionDefaults.AUTO_REENGAGE_ACTOR);
                log.info("Kill switch rengagé automatiquement après l'envoi du testeur d'ordre");
                journal.record(ExecutionDefaults.AUTO_REENGAGE_ACTOR, AdminAction.AUTO_REENGAGE, "order-test",
                        AdminActionLogService.payload("triggeredBy", actor), AdminActionOutcome.OK);
            }
        }
    }

    /** Relecture d'un ordre du testeur (lecture seule), tracée au journal ({@link AdminAction#ORDER_READ}). */
    public RealOrderResultDto reconcile(ReconcileRequest request, String actor) {
        String target = request == null ? "order-read" : "user:" + request.userId() + "/" + request.provider() + "/" + request.clOrdId();
        try {
            RealOrderResultDto result = orderTests.reconcileOrder(request);
            journal.record(actor, AdminAction.ORDER_READ, target, AdminActionLogService.payload("status", result.status(),
                    "phase", result.phase(), "filledSz", result.filledSz(), "avgPx", result.avgPx(), "message", result.message()),
                    AdminActionOutcome.OK);
            return result;
        } catch (IllegalArgumentException e) {
            journal.record(actor, AdminAction.ORDER_READ, target, AdminActionLogService.payload("reason", e.getMessage()),
                    AdminActionOutcome.REFUSED);
            throw e;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.ORDER_READ, target, AdminActionLogService.payload("error", e.getClass().getSimpleName()),
                    AdminActionOutcome.ERROR);
            throw e;
        }
    }

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
