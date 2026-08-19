package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Archivage sur inactivité prolongée (Palier 3, étape 6,
 * docs/etudes/etude-branchement-persistance-decision-engine.md §E pt 5). Prend une photo globale
 * (réutilise {@link DecisionScenarioSnapshotService} tel quel), puis évince de la mémoire active
 * (via {@link ScenarioEngine#evictOwner}/{@link DecisionEngine#evictOwner}) tout owner dont la
 * dernière connexion remonte à plus de {@link #ARCHIVAL_DELAY}. Ne supprime rien en base : la photo
 * + les événements restent, c'est ce qui permet la restauration à la reconnexion (cf. {@code
 * DecisionScenarioRestoreService#restoreOwner}, hook dans {@code AuthController}).
 *
 * <p>Le compte technique "System" (cf. {@code UserInitializer}, rôle {@code ROLE_SYS}) est
 * explicitement exclu des candidats à l'archivage (consigne de Clem, 2026-08-17) : il n'a jamais de
 * {@code lastLogin} à jour puisque personne ne s'y connecte, mais il est utilisé en interne pour
 * résoudre les credentials des providers externes ({@code IndicatorCredentialResolver}, {@code
 * MacroCredentialResolver}, {@code MediaCredentialResolver}). L'évincer casserait ces résolutions.
 */
@Service
@RequiredArgsConstructor
public class UserArchivalService {

    private static final Logger log = LoggerFactory.getLogger(UserArchivalService.class);

    // TODO parametrize — valeur de départ actée par Clem (2026-08-13), à externaliser en
    // property Spring quand ce palier sera éprouvé en usage réel.
    private static final Duration ARCHIVAL_DELAY = Duration.ofDays(60);

    // Compte technique jamais archivé, cf. javadoc de classe.
    private static final String SYSTEM_USERNAME = "System";

    private final UserRepository userRepository;
    private final DecisionScenarioSnapshotService snapshotService;
    private final ScenarioEngine scenarioEngine;
    private final DecisionEngine decisionEngine;
    private final DomainClock clock;

    public ArchivalResult archiveInactiveUsers() {
        Instant now = clock.now();
        Instant threshold = now.minus(ARCHIVAL_DELAY);

        // Photo globale avant toute éviction : garantit que chaque owner évincé a un état à jour
        // en base, sans avoir à isoler une photo par owner (réutilise le service existant tel
        // quel — cf. étape 4 du palier, décision de ne pas dupliquer ce mécanisme).
        snapshotService.takeSnapshot();

        List<User> candidates = userRepository
                .findByLastLoginBeforeAndArchivedAtIsNullAndUsernameNot(threshold, SYSTEM_USERNAME);
        for (User user : candidates) {
            ScenarioOwner owner = ScenarioOwner.of(user);
            scenarioEngine.evictOwner(owner);
            decisionEngine.evictOwner(owner);
            user.setArchivedAt(now);
        }
        userRepository.saveAll(candidates);

        log.info("UserArchivalService: {} utilisateur(s) archivé(s) (inactifs depuis {}).",
                candidates.size(), ARCHIVAL_DELAY);

        return new ArchivalResult(candidates.size(), now);
    }
}
