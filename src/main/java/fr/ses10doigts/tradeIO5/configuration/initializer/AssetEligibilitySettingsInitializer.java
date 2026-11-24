package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.market.AssetEligibilitySettings;
import fr.ses10doigts.tradeIO5.repository.market.AssetEligibilitySettingsRepository;
import fr.ses10doigts.tradeIO5.service.market.eligibility.AssetEligibilityDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Crée la ligne {@link AssetEligibilitySettings} (volume 24 h minimum = défaut du code) si elle est absente ; une ligne existante n'est jamais modifiée. */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(72)
public class AssetEligibilitySettingsInitializer implements CommandLineRunner {

    private final AssetEligibilitySettingsRepository repository;

    @Override
    public void run(String... args) {
        if (repository.existsById(AssetEligibilitySettings.SINGLETON_ID)) {
            log.debug("asset_eligibility_settings déjà présent");
            return;
        }
        repository.save(AssetEligibilitySettings.builder().id(AssetEligibilitySettings.SINGLETON_ID)
                .minVolumeUsd(AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD).updatedBy("initializer").build());
        log.info("asset_eligibility_settings créé : volume 24 h minimum {} USD", AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD);
    }
}
