package fr.ses10doigts.tradeIO5.security.repository;

import fr.ses10doigts.tradeIO5.security.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Palier 3, étape 6. Patron {@code @DataJpaTest} déjà utilisé ailleurs dans ce projet (ex.
 * {@code AssetProviderRepositoryTest}).
 */
@DataJpaTest
@DisplayName("UserRepository")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final Instant THRESHOLD = NOW.minusSeconds(60L * 24 * 3600);

    private User newUser(String username, Instant lastLogin, Instant archivedAt) {
        return User.builder()
                .username(username)
                .email(username + "@test.com")
                .password("hash")
                .roles(new HashSet<>())
                .enabled(true)
                .lastLogin(lastLogin)
                .archivedAt(archivedAt)
                .build();
    }

    @Test
    @DisplayName("findByLastLoginBeforeAndArchivedAtIsNull ne renvoie que l'utilisateur inactif et pas déjà archivé")
    void findByLastLoginBeforeAndArchivedAtIsNull_returnsOnlyInactiveNotYetArchivedUser() {
        User oldNotArchived = userRepository.save(
                newUser("oldNotArchived", THRESHOLD.minusSeconds(3600), null));
        userRepository.save(
                newUser("oldAlreadyArchived", THRESHOLD.minusSeconds(3600), NOW.minusSeconds(60)));
        userRepository.save(
                newUser("recent", THRESHOLD.plusSeconds(3600), null));

        List<User> candidates = userRepository.findByLastLoginBeforeAndArchivedAtIsNull(THRESHOLD);

        assertEquals(1, candidates.size());
        assertEquals(oldNotArchived.getId(), candidates.getFirst().getId());
    }
}
