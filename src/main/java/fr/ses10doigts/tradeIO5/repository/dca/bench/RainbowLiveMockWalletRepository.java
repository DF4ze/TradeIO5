package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RainbowLiveMockWalletRepository extends JpaRepository<RainbowLiveMockWallet, Long> {

    Optional<RainbowLiveMockWallet> findByPreset(RainbowLivePreset preset);

    List<RainbowLiveMockWallet> findByPresetIn(Collection<RainbowLivePreset> presets);
}
