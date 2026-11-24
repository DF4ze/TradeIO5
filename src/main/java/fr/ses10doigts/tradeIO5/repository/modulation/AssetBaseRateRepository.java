package fr.ses10doigts.tradeIO5.repository.modulation;

import fr.ses10doigts.tradeIO5.model.entity.modulation.AssetBaseRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetBaseRateRepository extends JpaRepository<AssetBaseRate, Long> {

    Optional<AssetBaseRate> findByAssetSymbol(String assetSymbol);
}
