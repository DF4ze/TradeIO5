package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.AssetDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.PlanDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.StepDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlanAsset;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Lecture des plans d'ordres de l'utilisateur (base uniquement, aucun appel exchange). Jamais le plan d'un autre. */
@Service
@RequiredArgsConstructor
public class OrderPlanQueryService {

    private final RainbowLiveOrderPlanRepository planRepository;
    private final DomainClock clock;
    private final fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderEventRepository eventRepository;

    private static final int MAX_PAGE_SIZE = 100;

    /** Journal d'audit paginé d'un plan du propriétaire (lecture seule) ; plan d'un autre ou inexistant => vide (404). */
    @Transactional(readOnly = true)
    public Optional<fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.EventPageDto> events(User user, Long planId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        return planRepository.findById(planId).filter(p -> p.getUser().getId().equals(user.getId())).map(plan -> {
            var result = eventRepository.findByPlanOrderByIdAsc(plan, org.springframework.data.domain.PageRequest.of(safePage, safeSize));
            var items = result.getContent().stream().map(e -> new fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.EventDto(
                    e.getId(), e.getStepId(), e.getClOrdId(), e.getType().name(), e.getPayload(), e.getCreatedAt())).toList();
            return new fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.EventPageDto(safePage, safeSize,
                    result.getTotalElements(), items);
        });
    }

    /** Plans courants (hors remplacés) entre deux jours inclus, du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<PlanDto> plans(User user, LocalDate from, LocalDate to) {
        Instant now = clock.now();
        return planRepository.findByUserAndStatusNotAndDayBetweenOrderByDayDescCreatedAtDesc(user, PlanStatus.SUPERSEDED, from, to)
                .stream().map(p -> toDto(p, now)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<PlanDto> latest(User user) {
        Instant now = clock.now();
        return planRepository.findFirstByUserAndStatusNotOrderByDayDescCreatedAtDesc(user, PlanStatus.SUPERSEDED)
                .map(p -> toDto(p, now));
    }

    static PlanDto toDto(RainbowLiveOrderPlan plan, Instant now) {
        List<AssetDto> assets = plan.getAssets().stream().map(a -> toDto(plan, a)).toList();
        return new PlanDto(plan.getId(), plan.getDay(), plan.getPass().name(), plan.getRevision(), plan.effectiveStatus(now),
                plan.getMode().name(), plan.getTotalCostPct(), plan.getFeeTestLevel(), plan.getBlockReason(), plan.getCreatedAt(),
                plan.getExpiresAt(), plan.getExecutionBlockReason(), plan.getExecutionStartedAt(),
                plan.getExecutionFinishedAt(), assets);
    }

    private static AssetDto toDto(RainbowLiveOrderPlan plan, RainbowLiveOrderPlanAsset a) {
        List<StepDto> steps = plan.getSteps().stream().filter(s -> s.getAssetSymbol().equals(a.getAssetSymbol()))
                .map(s -> new StepDto(s.getRank(), s.getInstId(), s.getSide(), s.getOrdType().name(), s.getSz(), s.getPx(),
                        s.getQuoteAmount(), s.getClOrdId(), s.getFeePct(), s.getSpreadPct(), s.getSlippagePct(), s.getEstimation(),
                        s.getStatus(), s.getFilledSz(), s.getAvgFillPx(), s.getFeeAmount(), s.getFeeCurrency(), s.getReceivedAmount(),
                        s.getReceivedCurrency(), s.getRealSlippagePct(), s.getLastError()))
                .toList();
        return new AssetDto(a.getAssetSymbol(), a.getAction(), a.getOutcome(), a.getBlockReason(), a.getCostPct(),
                a.getFeeTestLevel(), a.getWarning(), steps);
    }
}
