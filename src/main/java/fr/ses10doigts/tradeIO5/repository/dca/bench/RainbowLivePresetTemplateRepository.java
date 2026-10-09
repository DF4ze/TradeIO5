package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Accès aux templates système. Écriture réservée à {@code RainbowLivePresetTemplateService#ensureTemplates} (insertion
 * seule) : aucun autre code ne doit appeler {@code save}/{@code delete} (et l'entité refuse update/remove).
 */
public interface RainbowLivePresetTemplateRepository extends JpaRepository<RainbowLivePresetTemplate, Long> {

    List<RainbowLivePresetTemplate> findAllByOrderByAssetSymbolAscNameAsc();

    boolean existsByAssetSymbolAndName(String assetSymbol, String name);
}
