package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetAffectationRepository extends JpaRepository<AssetAffectation, Long> {

    Optional<AssetAffectation> findByUserAndAssetSymbol(User user, String assetSymbol);

    List<AssetAffectation> findByUser(User user);
}
