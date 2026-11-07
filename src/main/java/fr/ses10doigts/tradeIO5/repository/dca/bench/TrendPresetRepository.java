package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrendPresetRepository extends JpaRepository<TrendPreset, Long> {

    Optional<TrendPreset> findByAssetSymbolAndBaseRole(String assetSymbol, BasePresetRole baseRole);
}
