package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAthReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface RainbowAthReferenceRepository extends JpaRepository<RainbowAthReference, Long> {

    Optional<RainbowAthReference> findByAssetSymbolAndDay(String assetSymbol, LocalDate day);

    /** ATH valable à la fin du dernier jour strictement antérieur à {@code day}. */
    Optional<RainbowAthReference> findFirstByAssetSymbolAndDayBeforeOrderByDayDesc(String assetSymbol, LocalDate day);

    boolean existsByAssetSymbol(String assetSymbol);
}
