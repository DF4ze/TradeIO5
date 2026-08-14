package fr.ses10doigts.tradeIO5.security.repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import fr.ses10doigts.tradeIO5.security.model.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByUsername(String username);

	Boolean existsByUsername(String username);

	Boolean existsByEmail(String email);

	/**
	 * Candidats à l'archivage sur inactivité prolongée (Palier 3, étape 6) : utilisateurs dont la
	 * dernière connexion est antérieure au seuil, et pas déjà archivés (évite de retraiter un
	 * utilisateur déjà retiré de la mémoire active).
	 */
	List<User> findByLastLoginBeforeAndArchivedAtIsNull(Instant threshold);
}
