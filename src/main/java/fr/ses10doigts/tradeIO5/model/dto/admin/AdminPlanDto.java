package fr.ses10doigts.tradeIO5.model.dto.admin;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.AssetDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.PlanDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Plan d'ordres vu par l'admin (tous utilisateurs) : {@code status} est le statut effectif ({@code EXPIRED} une fois l'échéance atteinte). */
public record AdminPlanDto(Long id, Long userId, String username, LocalDate day, String pass, int revision, PlanStatus status,
                           BigDecimal totalCostPct, FeeTestLevel feeTestLevel, String blockReason, String executionBlockReason,
                           Instant expiresAt, List<AssetDto> assets) {

    public static AdminPlanDto of(Long userId, String username, PlanDto p) {
        return new AdminPlanDto(p.id(), userId, username, p.day(), p.pass(), p.revision(), p.status(), p.totalCostPct(),
                p.feeTestLevel(), p.blockReason(), p.executionBlockReason(), p.expiresAt(), p.assets());
    }
}
