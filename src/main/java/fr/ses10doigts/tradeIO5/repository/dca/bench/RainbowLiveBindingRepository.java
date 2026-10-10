package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RainbowLiveBindingRepository extends JpaRepository<RainbowLiveBinding, Long> {

    List<RainbowLiveBinding> findByUserOrderByPriorityAscAssetSymbolAsc(User user);

    Optional<RainbowLiveBinding> findByUserAndAssetSymbol(User user, String assetSymbol);

    boolean existsByPreset(RainbowLivePreset preset);
}
