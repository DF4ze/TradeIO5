package fr.ses10doigts.tradeIO5.model.dto.execution;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.execution.AssetPlanOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;

import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTO de lecture des plans d'ordres (dry-run, rien n'est envoyé) : aucune entité JPA exposée. */
public final class OrderPlanDtos {

    private OrderPlanDtos() {
    }

    /** {@code status} est le statut effectif : {@code EXPIRED} une fois {@code expiresAt} atteint. */
    public record PlanDto(Long id, LocalDate day, String pass, int revision, PlanStatus status, String mode,
                          BigDecimal totalCostPct, FeeTestLevel feeTestLevel, String blockReason, Instant createdAt,
                          Instant expiresAt, String executionBlockReason, Instant executionStartedAt,
                          Instant executionFinishedAt, List<AssetDto> assets) {
    }

    public record AssetDto(String assetSymbol, RainbowLiveAction action, AssetPlanOutcome outcome, PlanBlockReason blockReason,
                           BigDecimal costPct, FeeTestLevel feeTestLevel, String warning, List<StepDto> steps) {
    }

    public record StepDto(int rank, String instId, StepSide side, String ordType, BigDecimal sz, BigDecimal px,
                          BigDecimal quoteAmount, String clOrdId, BigDecimal feePct, BigDecimal spreadPct,
                          BigDecimal slippagePct, PathEstimation estimation, StepStatus status, BigDecimal filledSz,
                          BigDecimal avgFillPx, BigDecimal feeAmount, String feeCurrency, BigDecimal receivedAmount,
                          String receivedCurrency, BigDecimal realSlippagePct, String lastError) {
    }

    /** Évènement d'audit (lecture seule). {@code payload} = JSON sans secret, affiché en texte. */
    public record EventDto(Long id, Long stepId, String clOrdId, String type, String payload, Instant createdAt) {
    }

    public record EventPageDto(int page, int size, long totalElements, List<EventDto> items) {
    }

    /** État global affiché sur la page : mode, exécution réelle possible, kill switch. Aucun secret. */
    public record ExecutionStateDto(String mode, boolean liveExecution, boolean killSwitch) {
    }
}
