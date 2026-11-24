package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.configuration.initializer.AssetEligibilitySettingsInitializer;
import fr.ses10doigts.tradeIO5.model.entity.market.AssetBlocklist;
import fr.ses10doigts.tradeIO5.model.entity.market.AssetEligibilitySettings;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.repository.market.AssetEligibilitySettingsRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@DisplayName("Liste noire et réglage de volume : persistance, initializer, service")
class AssetEligibilityPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    @Autowired private AssetBlocklistRepository blocklist;
    @Autowired private AssetEligibilitySettingsRepository settingsRepository;

    private AssetEligibilitySettingsService service() {
        return new AssetEligibilitySettingsService(settingsRepository, new FixedDomainClock(NOW));
    }

    @Test
    @DisplayName("Liste noire : lecture par symbole, un symbole une seule fois")
    void blocklistUnique() {
        blocklist.saveAndFlush(AssetBlocklist.builder().assetSymbol("SCAM").reason("illiquide").createdBy("admin").createdAt(NOW).build());

        assertTrue(blocklist.existsByAssetSymbol("SCAM"));
        assertFalse(blocklist.existsByAssetSymbol("BTC"));
        assertThrows(DataIntegrityViolationException.class,
                () -> blocklist.saveAndFlush(AssetBlocklist.builder().assetSymbol("SCAM").createdBy("admin").createdAt(NOW).build()));
    }

    @Test
    @DisplayName("Sans ligne : défaut du code (1 000 000 USD)")
    void defaultWithoutRow() {
        assertEquals(0, AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD.compareTo(service().minVolumeUsd()));
        assertEquals(0, new BigDecimal("1000000").compareTo(service().minVolumeUsd()));
    }

    @Test
    @DisplayName("Initializer : crée la ligne au défaut, ne modifie jamais une ligne existante")
    void initializerIsIdempotent() {
        AssetEligibilitySettingsInitializer initializer = new AssetEligibilitySettingsInitializer(settingsRepository);
        initializer.run();
        assertEquals(0, new BigDecimal("1000000").compareTo(service().minVolumeUsd()));

        service().updateMinVolumeUsd(new BigDecimal("2500000"), "clem");
        initializer.run();

        assertEquals(1, settingsRepository.count());
        assertEquals(0, new BigDecimal("2500000").compareTo(service().minVolumeUsd()));
    }

    @Test
    @DisplayName("Édition : valeur, auteur et date enregistrés ; négatif ou nul refusé")
    void update() {
        service().updateMinVolumeUsd(new BigDecimal("500000.50"), "clem");

        AssetEligibilitySettings row = settingsRepository.findById(AssetEligibilitySettings.SINGLETON_ID).orElseThrow();
        assertEquals(0, new BigDecimal("500000.50").compareTo(row.getMinVolumeUsd()));
        assertEquals("clem", row.getUpdatedBy());
        assertEquals(NOW, row.getUpdatedAt());
        assertThrows(IllegalArgumentException.class, () -> service().updateMinVolumeUsd(new BigDecimal("-1"), "clem"));
        assertThrows(IllegalArgumentException.class, () -> service().updateMinVolumeUsd(null, "clem"));
    }
}
