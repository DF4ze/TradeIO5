package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.execution.AssetPlanOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;

import java.math.BigDecimal;
import java.util.List;

/** Résultat pur du {@link OrderPlanner} (aucune persistance, aucun {@code clOrdId} : posés à l'enregistrement). */
public record PlanDraft(List<AssetDraft> assets, PlanStatus status, BigDecimal totalCostPct, FeeTestLevel feeTestLevel,
                        String blockReason) {

    /** {@code steps} vide sauf si {@code outcome = OK}. */
    public record AssetDraft(String assetSymbol, int priority, Long walletId, RainbowLiveAction action,
                             AssetPlanOutcome outcome, PlanBlockReason blockReason, BigDecimal costPct,
                             FeeTestLevel feeTestLevel, String warning, List<StepDraft> steps) {
    }

    /** {@code rank} : 1 = première étape du chemin de l'actif (pont éventuel), puis l'achat / la vente. */
    public record StepDraft(int rank, String assetSymbol, String instId, StepSide side, BigDecimal sz, BigDecimal px,
                            BigDecimal quoteAmount, BigDecimal feePct, BigDecimal spreadPct, BigDecimal slippagePct,
                            PathEstimation estimation) {
    }
}
