package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveUserSettings;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RainbowLiveUserSettingsRepository extends JpaRepository<RainbowLiveUserSettings, Long> {

    Optional<RainbowLiveUserSettings> findByUser(User user);
}
