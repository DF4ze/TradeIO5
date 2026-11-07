package fr.ses10doigts.tradeIO5.repository.dca.bench.legacy;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy.LegacyRainbowPreset;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LegacyRainbowPresetRepository extends JpaRepository<LegacyRainbowPreset, Long> {

    List<LegacyRainbowPreset> findByUserOrderByAssetSymbolAscIdAsc(User user);
}
