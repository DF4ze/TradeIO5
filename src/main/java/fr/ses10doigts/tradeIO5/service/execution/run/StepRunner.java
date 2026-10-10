package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderStepRepository;
import fr.ses10doigts.tradeIO5.service.execution.exchange.Fill;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderNotFoundException;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderRejectedException;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderRequest;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderState;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import fr.ses10doigts.tradeIO5.service.execution.exchange.TradeCredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static fr.ses10doigts.tradeIO5.service.execution.run.OrderEventLog.payload;

/**
 * Cycle de vie d'UNE étape auprès de l'exchange : écriture préalable {@code SUBMITTED}, envoi, attente bornée de l'état,
 * lecture des remplissages, mise à jour de l'étape + {@code Transaction}, audit. Seul composant (avec {@link OrderExecutor})
 * à toucher le {@link SpotOrderPort}.
 * <ul>
 *   <li>Rejet certain (métier, clé refusée) => {@code REJECTED}. Doublon de {@code clOrdId} => relecture de l'état.</li>
 *   <li>Issue inconnue (timeout, réponse illisible, état non terminal, remplissages illisibles) => {@code UNKNOWN} : jamais de
 *       renvoi, réconciliation par {@code clOrdId} ({@link #reconcile}).</li>
 * </ul>
 */
@Slf4j
@Component
public class StepRunner {

    /** Étape à traiter. {@code sz} = taille envoyée (ou déjà envoyée pour une réconciliation). */
    public record StepContext(Long planId, Long walletId, Long stepId, String clOrdId, String instId, StepSide side,
                              BigDecimal sz, BigDecimal px, InstrumentInfo instrument, ApiCredential tradeCredential,
                              BigDecimal mid) {
    }

    /** Issue d'une étape ; {@code outcome} nul sans remplissage connu. */
    public record StepResult(StepStatus status, FillAccounting.Outcome outcome) {
    }

    private final ObjectProvider<SpotOrderPort> ports;
    private final RainbowLiveOrderStepRepository steps;
    private final FillRecorder recorder;
    private final OrderEventLog events;
    private final DomainClock clock;
    private final TransactionTemplate tx;
    private final Duration queryInterval;
    private final int queryAttempts;

    @Autowired
    public StepRunner(ObjectProvider<SpotOrderPort> ports, RainbowLiveOrderStepRepository steps, FillRecorder recorder,
                      OrderEventLog events, DomainClock clock, PlatformTransactionManager transactionManager,
                      @Value("${" + ExecutionDefaults.ORDER_QUERY_INTERVAL_PROPERTY + ":PT0.3S}") Duration queryInterval) {
        this(ports, steps, recorder, events, clock, transactionManager, queryInterval, ExecutionDefaults.ORDER_QUERY_ATTEMPTS);
    }

    StepRunner(ObjectProvider<SpotOrderPort> ports, RainbowLiveOrderStepRepository steps, FillRecorder recorder,
               OrderEventLog events, DomainClock clock, PlatformTransactionManager transactionManager,
               Duration queryInterval, int queryAttempts) {
        this.ports = ports;
        this.steps = steps;
        this.recorder = recorder;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.queryInterval = queryInterval;
        this.queryAttempts = queryAttempts;
    }

    // ------------------------------------------------------------------ envoi

    public StepResult send(StepContext c) {
        SpotOrderPort port = port();
        OrderRequest request = new OrderRequest(c.instId(), c.side(), StepOrderType.LIMIT_IOC, c.sz(), c.px(), c.clOrdId());
        tx.executeWithoutResult(status -> {
            RainbowLiveOrderStep step = steps.findById(c.stepId()).orElseThrow();
            step.setStatus(StepStatus.SUBMITTED);
            step.setSentSz(c.sz());
            step.setQuoteMid(c.mid());
            step.setSubmittedAt(clock.now());
            steps.save(step);
        });
        events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_SUBMITTING,
                payload("instId", c.instId(), "side", c.side(), "sz", c.sz(), "px", c.px(), "mid", c.mid()));
        log.info("Étape {} envoyée : {} {} sz={} plafond={}", c.clOrdId(), c.side(), c.instId(), c.sz(), c.px());
        try {
            var ack = port.submit(c.tradeCredential(), request);
            setOrdId(c, ack.ordId());
            events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_ACK, payload("ordId", ack.ordId()));
        } catch (OrderRejectedException e) {
            if (!e.duplicateClientOrderId()) {
                return finishRejected(c, e.getCode(), e.getMessage());
            }
            events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_RECONCILED, payload("reason", "DUPLICATE_CL_ORD_ID"));
        } catch (TradeCredentialRejectedException e) {
            events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.ALERT, payload("reason", "CREDENTIAL_REJECTED"));
            log.error("Clé Trade refusée par l'exchange pour l'étape {}", c.clOrdId());
            return finishRejected(c, "CREDENTIAL_REJECTED", e.getMessage());
        } catch (RuntimeException e) {
            return markUnknown(c, "EXCHANGE_UNAVAILABLE", e.getClass().getSimpleName());
        }
        return await(c, port);
    }

    private StepResult await(StepContext c, SpotOrderPort port) {
        for (int attempt = 1; attempt <= queryAttempts; attempt++) {
            try {
                OrderState state = port.query(c.tradeCredential(), c.instId(), c.clOrdId());
                if (state.terminal()) {
                    return finalizeFrom(c, port, state);
                }
            } catch (OrderNotFoundException e) {
                log.debug("Ordre {} pas encore visible (tentative {})", c.clOrdId(), attempt);
            } catch (RuntimeException e) {
                log.debug("Lecture de l'ordre {} impossible (tentative {}) : {}", c.clOrdId(), attempt, e.getClass().getSimpleName());
            }
            if (attempt < queryAttempts) {
                pause();
            }
        }
        return markUnknown(c, "NO_TERMINAL_STATE", null);
    }

    // ------------------------------------------------------------------ réconciliation

    /** Relit l'ordre par {@code clOrdId} et clôt l'étape si possible. Ne renvoie jamais d'ordre. */
    public StepResult reconcile(StepContext c) {
        SpotOrderPort port = port();
        OrderState state;
        try {
            state = port.query(c.tradeCredential(), c.instId(), c.clOrdId());
        } catch (OrderNotFoundException e) {
            tx.executeWithoutResult(status -> {
                RainbowLiveOrderStep step = steps.findById(c.stepId()).orElseThrow();
                step.setStatus(StepStatus.CANCELED);
                step.setLastError("NOT_FOUND_AT_EXCHANGE");
                step.setFinishedAt(clock.now());
                steps.save(step);
            });
            events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_RECONCILED, payload("result", "NOT_FOUND_AT_EXCHANGE"));
            log.warn("Étape {} introuvable côté exchange : jamais acceptée, annulée", c.clOrdId());
            return new StepResult(StepStatus.CANCELED, null);
        } catch (RuntimeException e) {
            events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_RECONCILED,
                    payload("result", "UNAVAILABLE", "error", e.getClass().getSimpleName()));
            return new StepResult(StepStatus.UNKNOWN, null);
        }
        events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_RECONCILED,
                payload("phase", state.phase(), "accFillSz", state.accFillSz(), "avgPx", state.avgPx()));
        if (!state.terminal()) {
            return new StepResult(StepStatus.UNKNOWN, null);
        }
        setOrdId(c, state.ordId());
        return finalizeFrom(c, port, state);
    }

    // ------------------------------------------------------------------ issues

    private StepResult finalizeFrom(StepContext c, SpotOrderPort port, OrderState state) {
        List<Fill> fills = List.of();
        boolean filled = state.accFillSz() != null && state.accFillSz().signum() > 0;
        if (filled) {
            try {
                fills = port.fills(c.tradeCredential(), c.instId(), state.ordId());
            } catch (RuntimeException e) {
                return markUnknown(c, "FILLS_UNAVAILABLE", e.getClass().getSimpleName());
            }
            if (fills.isEmpty()) {
                return markUnknown(c, "FILLS_UNAVAILABLE", "empty");
            }
        }
        FillAccounting.Outcome outcome = FillAccounting.of(c.side(), c.instrument(), c.sz(), state, fills);
        BigDecimal slippage = FillAccounting.realSlippagePct(c.side(), outcome.avgPx(), c.mid());
        List<Fill> recorded = fills;
        tx.executeWithoutResult(status -> {
            RainbowLiveOrderStep step = steps.findById(c.stepId()).orElseThrow();
            step.setStatus(outcome.status());
            step.setOrdId(state.ordId());
            step.setSentSz(c.sz());
            step.setFilledSz(outcome.filledSz());
            step.setAvgFillPx(outcome.avgPx());
            step.setFeeAmount(outcome.fee());
            step.setFeeCurrency(outcome.feeCurrency());
            step.setReceivedAmount(outcome.received());
            step.setReceivedCurrency(outcome.receivedCurrency());
            step.setRealSlippagePct(slippage);
            step.setFinishedAt(clock.now());
            step.setLastError(null);
            steps.save(step);
            recorder.record(c.walletId(), c.instrument().base(), recorded);
        });
        events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_RESULT,
                payload("status", outcome.status(), "filledSz", outcome.filledSz(), "avgPx", outcome.avgPx(), "fee", outcome.fee(),
                        "feeCcy", outcome.feeCurrency(), "received", outcome.received(), "receivedCcy", outcome.receivedCurrency(),
                        "realSlippagePct", slippage));
        log.info("Étape {} : {} rempli={} prix moyen={} frais={} {}", c.clOrdId(), outcome.status(), outcome.filledSz(),
                outcome.avgPx(), outcome.fee(), outcome.feeCurrency());
        if (outcome.status() == StepStatus.PARTIAL) {
            BigDecimal remaining = c.sz().subtract(outcome.filledSz());
            events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.PARTIAL_REMAINDER,
                    payload("remainingSz", remaining, "note", "reliquat reporté au cycle suivant"));
            events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.ALERT, payload("reason", "PARTIAL_FILL"));
            log.warn("Étape {} remplie partiellement : reliquat {} reporté au cycle suivant", c.clOrdId(), remaining);
        }
        return new StepResult(outcome.status(), outcome);
    }

    private StepResult finishRejected(StepContext c, String code, String message) {
        tx.executeWithoutResult(status -> {
            RainbowLiveOrderStep step = steps.findById(c.stepId()).orElseThrow();
            step.setStatus(StepStatus.REJECTED);
            step.setLastError(code);
            step.setFinishedAt(clock.now());
            steps.save(step);
        });
        events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_RESULT,
                payload("status", StepStatus.REJECTED, "code", code, "message", message));
        log.warn("Étape {} rejetée : {}", c.clOrdId(), code);
        return new StepResult(StepStatus.REJECTED, null);
    }

    private StepResult markUnknown(StepContext c, String reason, String detail) {
        tx.executeWithoutResult(status -> {
            RainbowLiveOrderStep step = steps.findById(c.stepId()).orElseThrow();
            step.setStatus(StepStatus.UNKNOWN);
            step.setLastError(reason);
            steps.save(step);
        });
        events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.STEP_UNKNOWN, payload("reason", reason, "detail", detail));
        events.record(c.planId(), c.stepId(), c.clOrdId(), OrderEventType.ALERT, payload("reason", "STEP_UNKNOWN"));
        log.error("Étape {} : issue inconnue ({}), réconciliation par clOrdId requise, aucun renvoi", c.clOrdId(), reason);
        return new StepResult(StepStatus.UNKNOWN, null);
    }

    private void setOrdId(StepContext c, String ordId) {
        if (ordId == null || ordId.isBlank()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            RainbowLiveOrderStep step = steps.findById(c.stepId()).orElseThrow();
            step.setOrdId(ordId);
            steps.save(step);
        });
    }

    private SpotOrderPort port() {
        SpotOrderPort port = ports.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("Port d'ordres indisponible (mode LIVE non déverrouillé)");
        }
        return port;
    }

    private void pause() {
        if (queryInterval.isZero()) {
            return;
        }
        try {
            Thread.sleep(queryInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
