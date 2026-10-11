package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RainbowLiveBindingRepository extends JpaRepository<RainbowLiveBinding, Long> {

    List<RainbowLiveBinding> findByUserOrderByPriorityAscAssetSymbolAsc(User user);

    /** Tous les bindings (supervision admin), groupés par utilisateur. */
    List<RainbowLiveBinding> findAllByOrderByUserIdAscPriorityAscAssetSymbolAsc();

    /** Clé d'unicité : un binding par (utilisateur, actif, wallet). */
    Optional<RainbowLiveBinding> findByUserAndAssetSymbolAndWallet_Id(User user, String assetSymbol, Long walletId);

    /** Un preset peut alimenter plusieurs liens (un par wallet). */
    List<RainbowLiveBinding> findAllByPreset(RainbowLivePreset preset);

    boolean existsByPreset(RainbowLivePreset preset);
}
