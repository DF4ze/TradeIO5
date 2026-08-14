package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verrou anti-doublon par owner (Palier 3, étape 7, décision 2) : empêche deux refreshs concurrents
 * pour le même owner (mutex) et impose un délai minimal entre deux refreshs successifs (throttle),
 * pour qu'un cron, une relance manuelle (endpoint admin) et une future auto-sync à la connexion ne se
 * marchent jamais dessus.
 */
@Component
public class OwnerRefreshGuard {

    // TODO parametrize — valeur de départ actée par Clem (2026-08-14), à externaliser en property
    // Spring quand ce palier sera éprouvé en usage réel.
    private static final Duration MIN_INTERVAL = Duration.ofHours(1);

    private final Map<ScenarioOwner, OwnerRefreshState> states = new ConcurrentHashMap<>();

    /**
     * Tente d'acquérir le verrou pour cet owner : refuse si un refresh est déjà en cours (mutex) ou
     * si le précédent s'est terminé il y a moins de {@link #MIN_INTERVAL} (throttle). Les deux
     * mécanismes sont volontairement combinés dans une seule méthode — cf. étude §E pt 6 ("un seul
     * refresh actif à la fois, nouveau refresh bloqué tant qu'un délai minimal ne s'est pas écoulé").
     */
    public boolean tryAcquire(ScenarioOwner owner, Instant now) {
        OwnerRefreshState state = states.computeIfAbsent(owner, k -> new OwnerRefreshState());
        synchronized (state) {
            if (state.running) {
                return false;
            }
            if (state.lastCompletedAt != null
                    && state.lastCompletedAt.plus(MIN_INTERVAL).isAfter(now)) {
                return false;
            }
            state.running = true;
            return true;
        }
    }

    /** À appeler dans un {@code finally}, systématiquement après un {@link #tryAcquire} réussi. */
    public void release(ScenarioOwner owner, Instant completedAt) {
        OwnerRefreshState state = states.get(owner);
        if (state == null) return;
        synchronized (state) {
            state.running = false;
            state.lastCompletedAt = completedAt;
        }
    }

    private static class OwnerRefreshState {
        boolean running = false;
        Instant lastCompletedAt = null;
    }
}
