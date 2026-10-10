package fr.ses10doigts.tradeIO5.repository.execution;

import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderEvent;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Journal d'audit en ajout seul : volontairement SANS {@code JpaRepository} (aucune suppression ni mise à jour exposée). */
public interface RainbowLiveOrderEventRepository extends Repository<RainbowLiveOrderEvent, Long> {

    <S extends RainbowLiveOrderEvent> S save(S event);

    Page<RainbowLiveOrderEvent> findByPlanOrderByIdAsc(RainbowLiveOrderPlan plan, Pageable pageable);

    long countByPlan(RainbowLiveOrderPlan plan);
}
