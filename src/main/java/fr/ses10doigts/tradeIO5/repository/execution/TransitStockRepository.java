package fr.ses10doigts.tradeIO5.repository.execution;

import fr.ses10doigts.tradeIO5.model.entity.execution.TransitStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransitStockRepository extends JpaRepository<TransitStock, Long> {

    /** Stocks actifs (non résolus) d'un wallet. */
    List<TransitStock> findByWalletIdAndResolvedAtIsNull(Long walletId);

    /** Un stock a-t-il déjà été créé pour cette étape d'origine (idempotence de la réconciliation) ? */
    boolean existsByOriginPlanIdAndOriginStepRankAndWalletIdAndTargetAsset(Long originPlanId, Integer originStepRank, Long walletId,
                                                                           String targetAsset);

    /** Stocks actifs d'un wallet visant un actif donné. */
    List<TransitStock> findByWalletIdAndTargetAssetAndResolvedAtIsNull(Long walletId, String targetAsset);
}
