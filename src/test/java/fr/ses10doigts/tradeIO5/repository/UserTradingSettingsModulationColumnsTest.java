package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@DisplayName("UserTradingSettings : colonnes de modulation nullables")
class UserTradingSettingsModulationColumnsTest {

    @Autowired
    private UserTradingSettingsRepository repository;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Ligne sans réglage de modulation => colonnes nulles ; valeurs persistées relues")
    void nullableAndPersisted() {
        User user = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        repository.saveAndFlush(UserTradingSettings.builder().user(user).riskCursor(4).build());

        UserTradingSettings row = repository.findByUser(user).orElseThrow();
        assertNull(row.getExposureMin());
        assertNull(row.getBias());

        row.setExposureMin(20d);
        row.setBias(-0.3);
        repository.saveAndFlush(row);
        UserTradingSettings reread = repository.findByUser(user).orElseThrow();
        assertEquals(20d, reread.getExposureMin());
        assertEquals(-0.3, reread.getBias());
        assertEquals(4, reread.getRiskCursor());
    }
}
