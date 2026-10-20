package fr.ses10doigts.tradeIO5.repository.execution;

import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlanAsset;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RainbowLiveOrderPlanAssetRepository extends JpaRepository<RainbowLiveOrderPlanAsset, Long> {

    /**
     * Lignes d'un binding (user, wallet, actif) dans les plans courants (hors remplacés) STRICTEMENT avant {@code day}, la plus
     * récente d'abord : la première porte le carry sortant courant, quel que soit l'écart de jours.
     */
    @Query("select a from RainbowLiveOrderPlanAsset a where a.plan.user = :user and a.plan.status <> :excluded "
            + "and a.plan.day < :day and a.walletId = :walletId and a.assetSymbol = :symbol "
            + "order by a.plan.day desc, a.plan.revision desc")
    List<RainbowLiveOrderPlanAsset> latestBefore(@Param("user") User user, @Param("excluded") PlanStatus excluded,
                                                 @Param("day") LocalDate day, @Param("walletId") Long walletId,
                                                 @Param("symbol") String symbol, Pageable page);
}
