package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RainbowLivePresetRepository extends JpaRepository<RainbowLivePreset, Long> {

    List<RainbowLivePreset> findByUserOrderByAssetSymbolAscNameAsc(User user);

    List<RainbowLivePreset> findByUserAndAssetSymbolOrderByNameAsc(User user, String assetSymbol);

    Optional<RainbowLivePreset> findByUserAndAssetSymbolAndName(User user, String assetSymbol, String name);

    boolean existsByUserAndAssetSymbol(User user, String assetSymbol);
}
