package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 3, étape 7 (décision 2). Vérifie le double mécanisme (mutex + throttle) de
 * {@link OwnerRefreshGuard} et son isolation par owner.
 */
@DisplayName("OwnerRefreshGuard")
class OwnerRefreshGuardTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private final ScenarioOwner ownerA = ScenarioOwner.user("ownerA");
    private final ScenarioOwner ownerB = ScenarioOwner.user("ownerB");

    @Test
    @DisplayName("tryAcquire puis tryAcquire immédiat pour le même owner => false (mutex)")
    void tryAcquire_thenImmediateTryAcquire_returnsFalse_mutex() {
        OwnerRefreshGuard guard = new OwnerRefreshGuard();

        assertTrue(guard.tryAcquire(ownerA, NOW));
        assertFalse(guard.tryAcquire(ownerA, NOW), "un refresh déjà en cours doit bloquer un second tryAcquire");
    }

    @Test
    @DisplayName("tryAcquire, release, puis tryAcquire immédiat => false (throttle, < 1h écoulée)")
    void tryAcquire_release_thenImmediateTryAcquire_returnsFalse_throttle() {
        OwnerRefreshGuard guard = new OwnerRefreshGuard();

        assertTrue(guard.tryAcquire(ownerA, NOW));
        guard.release(ownerA, NOW);

        assertFalse(guard.tryAcquire(ownerA, NOW.plusSeconds(60)),
                "moins d'1h écoulée depuis le dernier release doit bloquer un nouveau tryAcquire");
    }

    @Test
    @DisplayName("tryAcquire, release, puis tryAcquire avec now avancé de plus d'1h => true")
    void tryAcquire_release_thenTryAcquireAfterMinInterval_returnsTrue() {
        OwnerRefreshGuard guard = new OwnerRefreshGuard();

        assertTrue(guard.tryAcquire(ownerA, NOW));
        guard.release(ownerA, NOW);

        assertTrue(guard.tryAcquire(ownerA, NOW.plus(Duration.ofHours(1).plusSeconds(1))),
                "plus d'1h écoulée depuis le dernier release doit autoriser un nouveau tryAcquire");
    }

    @Test
    @DisplayName("Deux owners distincts n'interfèrent jamais l'un avec l'autre")
    void twoDistinctOwners_neverInterfere() {
        OwnerRefreshGuard guard = new OwnerRefreshGuard();

        assertTrue(guard.tryAcquire(ownerA, NOW), "ownerA doit pouvoir acquérir son propre verrou");
        assertTrue(guard.tryAcquire(ownerB, NOW), "ownerB doit pouvoir acquérir son propre verrou, indépendamment d'ownerA");

        assertFalse(guard.tryAcquire(ownerA, NOW), "ownerA reste verrouillé (mutex) tant qu'il n'a pas release");
        guard.release(ownerB, NOW);
        assertFalse(guard.tryAcquire(ownerA, NOW), "release(ownerB) ne doit jamais affecter le verrou d'ownerA");
    }

    @Test
    @DisplayName("Concurrence réelle : plusieurs threads appelant tryAcquire pour le même owner simultanément => un seul obtient true")
    void concurrentTryAcquire_sameOwner_onlyOneSucceeds() throws InterruptedException {
        OwnerRefreshGuard guard = new OwnerRefreshGuard();
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (guard.tryAcquire(ownerA, NOW)) {
                        successCount.incrementAndGet();
                    }
                });
            }

            ready.await();
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "tous les threads doivent se terminer");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, successCount.get(), "un seul thread doit obtenir le verrou pour le même owner simultanément");
    }
}
