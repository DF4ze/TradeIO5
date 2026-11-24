package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.entity.market.AssetEligibilitySettings;
import fr.ses10doigts.tradeIO5.repository.market.AssetEligibilitySettingsRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Réglage admin « volume 24 h minimum » (ligne unique). Aucune ligne => défaut du code. L'écran d'édition arrive avec le lot Admin. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetEligibilitySettingsService {

    private final AssetEligibilitySettingsRepository repository;
    private final DomainClock clock;

    /** Volume spot 24 h minimum (USD). */
    public BigDecimal minVolumeUsd() {
        return repository.findById(AssetEligibilitySettings.SINGLETON_ID).map(AssetEligibilitySettings::getMinVolumeUsd)
                .orElse(AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD);
    }

    /** @throws IllegalArgumentException valeur absente ou négative */
    @Transactional
    public void updateMinVolumeUsd(BigDecimal value, String actor) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Le volume minimum doit être positif ou nul");
        }
        AssetEligibilitySettings row = repository.findById(AssetEligibilitySettings.SINGLETON_ID)
                .orElseGet(() -> AssetEligibilitySettings.builder().id(AssetEligibilitySettings.SINGLETON_ID).build());
        BigDecimal before = row.getMinVolumeUsd();
        row.setMinVolumeUsd(value);
        row.setUpdatedBy(actor);
        row.setUpdatedAt(clock.now());
        repository.save(row);
        log.info("Volume 24 h minimum : {} -> {} USD par {}", before, value, actor);
    }
}
