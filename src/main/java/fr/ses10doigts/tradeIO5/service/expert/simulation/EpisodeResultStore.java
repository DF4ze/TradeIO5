package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.service.expert.simulation.EpisodeTimeline.AssetSeries;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Séries d'épisodes d'un rejeu gardées en mémoire (même TTL et même plafond par utilisateur que les simulations) pour recalculer
 * épisodes et répartition du gain sur une sous-plage SANS rejouer. Rien n'est persisté.
 */
@Component
@RequiredArgsConstructor
public class EpisodeResultStore {

    /** Rejeu conservé : séries par actif, plage couverte, notes du rejeu. */
    public record Stored(String id, Long ownerId, Instant createdAt, List<AssetSeries> series, LocalDate from, LocalDate to,
                         List<String> notes) {
    }

    private final ConcurrentMap<String, Stored> store = new ConcurrentHashMap<>();
    private final ExpertSimulationSettings settings;
    private final DomainClock clock;

    public Stored put(Long ownerId, List<AssetSeries> series, LocalDate from, LocalDate to, List<String> notes) {
        Instant now = clock.now();
        purge(now);
        while (store.values().stream().filter(s -> s.ownerId().equals(ownerId)).count() >= settings.maxResultsPerUser()) {
            store.values().stream().filter(s -> s.ownerId().equals(ownerId)).min(Comparator.comparing(Stored::createdAt))
                    .ifPresent(s -> store.remove(s.id()));
        }
        Stored stored = new Stored(UUID.randomUUID().toString(), ownerId, now, series, from, to, notes);
        store.put(stored.id(), stored);
        return stored;
    }

    /** Rejeu de l'utilisateur ou {@code null} (inconnu, expiré ou d'un autre utilisateur). */
    public Stored get(Long ownerId, String id) {
        purge(clock.now());
        Stored s = id == null ? null : store.get(id);
        return s == null || !s.ownerId().equals(ownerId) ? null : s;
    }

    private void purge(Instant now) {
        store.values().removeIf(s -> !s.createdAt().plus(settings.resultTtl()).isAfter(now));
    }
}
