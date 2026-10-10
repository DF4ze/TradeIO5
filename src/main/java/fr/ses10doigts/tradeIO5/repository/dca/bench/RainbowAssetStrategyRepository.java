package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAssetStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Accès aux stratégies Actif. Insertion par {@code RainbowAssetStrategyService#ensureStrategies}, modification par
 * {@code RainbowAssetStrategyService#update} ; aucune suppression (l'entité la refuse).
 */
public interface RainbowAssetStrategyRepository extends JpaRepository<RainbowAssetStrategy, Long> {

    List<RainbowAssetStrategy> findAllByOrderByAssetSymbolAscNameAsc();

    boolean existsByAssetSymbolAndName(String assetSymbol, String name);
}
