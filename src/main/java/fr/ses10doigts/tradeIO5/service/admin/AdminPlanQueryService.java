package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminPlanDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.EventPageDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderEventRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanQueryService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Lecture admin des plans d'ordres, tous utilisateurs (base uniquement, aucun port d'ordre, aucun appel exchange). */
@Service
@RequiredArgsConstructor
public class AdminPlanQueryService {

    private final RainbowLiveOrderPlanRepository planRepository;
    private final RainbowLiveOrderEventRepository eventRepository;
    private final DomainClock clock;

    /**
     * Plans courants (révisions remplacées exclues) entre deux jours UTC inclus (défaut : {@value ExecutionDefaults#ADMIN_PLAN_WINDOW_DAYS}
     * derniers jours), filtrables par utilisateur et par statut <b>effectif</b>.
     */
    @Transactional(readOnly = true)
    public List<AdminPlanDto> plans(LocalDate from, LocalDate to, Long userId, PlanStatus status) {
        Instant now = clock.now();
        LocalDate end = to != null ? to : now.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate start = from != null ? from : end.minusDays(ExecutionDefaults.ADMIN_PLAN_WINDOW_DAYS);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from (" + start + ") postérieur à to (" + end + ")");
        }
        return planRepository.findAdminPlans(PlanStatus.SUPERSEDED, start, end, userId).stream()
                .map(p -> AdminPlanDto.of(p.getUser().getId(), p.getUser().getUsername(), OrderPlanQueryService.toDto(p, now)))
                .filter(p -> status == null || p.status() == status).toList();
    }

    /** Audit du plan (tous utilisateurs), paginé, du plus ancien au plus récent ; plan inconnu => vide (404). */
    @Transactional(readOnly = true)
    public Optional<EventPageDto> events(Long planId, int page, int size) {
        int safeSize = OrderPlanQueryService.safeSize(size);
        int safePage = OrderPlanQueryService.safePage(page);
        return planRepository.findById(planId).map(plan -> OrderPlanQueryService.toEventPage(safePage, safeSize,
                eventRepository.findByPlanOrderByIdAsc(plan, PageRequest.of(safePage, safeSize))));
    }
}
