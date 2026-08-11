package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@DisplayName("UserTradingSettingsRepository")
class UserTradingSettingsRepositoryTest {

    @Autowired
    private UserTradingSettingsRepository userTradingSettingsRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("findByUser retrouve les réglages persistés pour cet utilisateur")
    void findByUser_returnsPersistedSettings() {
        User user = userRepository.save(User.builder()
                .username("alice")
                .email("alice@example.com")
                .password("irrelevant")
                .build());

        userTradingSettingsRepository.save(UserTradingSettings.builder()
                .user(user)
                .riskCursor(7)
                .build());

        Optional<UserTradingSettings> found = userTradingSettingsRepository.findByUser(user);

        assertTrue(found.isPresent());
        assertEquals(7, found.get().getRiskCursor());
    }

    @Test
    @DisplayName("La contrainte unique user_id empêche deux settings pour le même utilisateur")
    void uniqueConstraint_preventsDuplicateSettingsForSameUser() {
        User user = userRepository.save(User.builder()
                .username("bob")
                .email("bob@example.com")
                .password("irrelevant")
                .build());

        userTradingSettingsRepository.saveAndFlush(UserTradingSettings.builder()
                .user(user)
                .riskCursor(3)
                .build());

        UserTradingSettings duplicate = UserTradingSettings.builder()
                .user(user)
                .riskCursor(8)
                .build();

        assertThrows(DataIntegrityViolationException.class,
                () -> userTradingSettingsRepository.saveAndFlush(duplicate));
    }
}
