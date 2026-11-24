package fr.ses10doigts.tradeIO5.repository.market;

import fr.ses10doigts.tradeIO5.model.entity.market.AssetBlocklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetBlocklistRepository extends JpaRepository<AssetBlocklist, Long> {

    boolean existsByAssetSymbol(String assetSymbol);

    Optional<AssetBlocklist> findByAssetSymbol(String assetSymbol);
}
