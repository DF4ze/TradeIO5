package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.security.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 2 (étape 1) : conversion canonique {@code User} <-> {@code ScenarioOwner}.
 * Cf. {@code MultiUserIsolationIntegrationTest} pour la version avec de vrais utilisateurs
 * persistés en base (@DataJpaTest).
 */
@DisplayName("ScenarioOwner - conversion User")
class ScenarioOwnerUserTest {

    @Test
    @DisplayName("of(user) avec id=42 produit un UserOwner dont getId() vaut \"42\"")
    void of_withPersistedUser_producesUserOwnerWithMatchingId() {
        User user = User.builder().id(42L).username("clem").build();

        ScenarioOwner owner = ScenarioOwner.of(user);

        assertTrue(owner instanceof ScenarioOwner.UserOwner);
        assertEquals("42", owner.getId());
    }

    @Test
    @DisplayName("of(null) lève IllegalArgumentException")
    void of_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioOwner.of(null));
    }

    @Test
    @DisplayName("of(user) avec id=null lève IllegalArgumentException")
    void of_userWithNullId_throws() {
        User user = User.builder().username("clem").build();

        assertThrows(IllegalArgumentException.class, () -> ScenarioOwner.of(user));
    }

    @Test
    @DisplayName("Round-trip asUserId() sur le résultat de of(user) retourne Optional.of(42L)")
    void asUserId_roundTripsAfterOf() {
        User user = User.builder().id(42L).username("clem").build();

        ScenarioOwner owner = ScenarioOwner.of(user);

        assertEquals(Optional.of(42L), owner.asUserId());
    }

    @Test
    @DisplayName("asUserId() sur SYSTEM retourne Optional.empty()")
    void asUserId_onSystemOwner_isEmpty() {
        assertEquals(Optional.empty(), ScenarioOwner.SYSTEM.asUserId());
    }
}
