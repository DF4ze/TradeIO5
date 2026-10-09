package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveTrendConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RainbowLiveTrendConfigRepository extends JpaRepository<RainbowLiveTrendConfig, Long> {

    Optional<RainbowLiveTrendConfig> findByPreset(RainbowLivePreset preset);

    List<RainbowLiveTrendConfig> findByPresetIn(Collection<RainbowLivePreset> presets);
}
