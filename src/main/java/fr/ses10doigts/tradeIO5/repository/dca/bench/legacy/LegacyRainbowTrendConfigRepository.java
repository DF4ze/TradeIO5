package fr.ses10doigts.tradeIO5.repository.dca.bench.legacy;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy.LegacyRainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy.LegacyRainbowTrendConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LegacyRainbowTrendConfigRepository extends JpaRepository<LegacyRainbowTrendConfig, Long> {

    Optional<LegacyRainbowTrendConfig> findByPreset(LegacyRainbowPreset preset);
}
