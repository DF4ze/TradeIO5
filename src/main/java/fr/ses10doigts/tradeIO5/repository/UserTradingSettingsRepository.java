package fr.ses10doigts.tradeIO5.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface UserTradingSettingsRepository extends JpaRepository<UserTradingSettings, Long> {

    Optional<UserTradingSettings> findByUser(User user);
}
