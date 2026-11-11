package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RainbowLiveBindingRepository extends JpaRepository<RainbowLiveBinding, Long> {

    /** Liens actifs de l'utilisateur (les liens archivés sont exclus). */
    @Query("select b from RainbowLiveBinding b where b.user = :user and b.archivedAt is null order by b.priority asc, b.assetSymbol asc")
    List<RainbowLiveBinding> findByUserOrderByPriorityAscAssetSymbolAsc(@Param("user") User user);

    /** Tous les bindings (supervision admin), groupés par utilisateur. */
    @Query("select b from RainbowLiveBinding b where b.archivedAt is null order by b.user.id asc, b.priority asc, b.assetSymbol asc")
    List<RainbowLiveBinding> findAllByOrderByUserIdAscPriorityAscAssetSymbolAsc();

    /** Clé d'unicité : un binding par (utilisateur, actif, wallet), archivé compris (à filtrer par l'appelant). */
    Optional<RainbowLiveBinding> findByUserAndAssetSymbolAndWallet_Id(User user, String assetSymbol, Long walletId);

    /** Une Affectation peut alimenter plusieurs liens (un par wallet). */
    List<RainbowLiveBinding> findAllByAffectation(AssetAffectation affectation);

    /** Vrai si au moins un lien non archivé référence l'Affectation (c'est ce qui la fait jouer par la passe). */
    boolean existsByAffectationAndArchivedAtIsNull(AssetAffectation affectation);

    /** Affectations référencées par au moins un lien non archivé de l'utilisateur. */
    @Query("select distinct b.affectation from RainbowLiveBinding b where b.user = :user and b.archivedAt is null and b.affectation is not null")
    List<AssetAffectation> findActiveAffectationsByUser(@Param("user") User user);
}
