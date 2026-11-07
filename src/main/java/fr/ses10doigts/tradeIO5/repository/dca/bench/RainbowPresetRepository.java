package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;

import java.util.Optional;

public interface RainbowPresetRepository extends ExpertPresetRepository<RainbowPreset> {

    Optional<RainbowPreset> findByAssetSymbolAndBaseRole(String assetSymbol, BasePresetRole baseRole);
}
