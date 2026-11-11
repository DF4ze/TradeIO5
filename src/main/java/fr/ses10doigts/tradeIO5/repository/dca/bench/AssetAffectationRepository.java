package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssetAffectationRepository extends JpaRepository<AssetAffectation, Long> {

    List<AssetAffectation> findByUserAndAssetSymbolOrderByIdAsc(User user, String assetSymbol);

    /** Instance Système de l'utilisateur sur l'actif (au plus une). */
    Optional<AssetAffectation> findByUserAndAssetSymbolAndSystemTrue(User user, String assetSymbol);

    boolean existsByUserAndAssetSymbolAndName(User user, String assetSymbol, String name);

    List<AssetAffectation> findByUser(User user);

    /** Affectations (de tous les utilisateurs) qui installent ce Trend. */
    List<AssetAffectation> findByTrendPreset(TrendPreset preset);

    /** Affectations (de tous les utilisateurs) qui installent ce Rainbow en UP ou en DOWN. */
    @Query("select a from AssetAffectation a where a.rainbowUp = :preset or a.rainbowDown = :preset")
    List<AssetAffectation> findUsingRainbow(@Param("preset") RainbowPreset preset);
}
