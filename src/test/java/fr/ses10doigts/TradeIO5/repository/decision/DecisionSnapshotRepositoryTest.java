package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.tree.decision.DecisionSnapshotEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 3, étape 4 (étape 5 du prompt d'implémentation). Patron {@code AssetProviderRepositoryTest}.
 */
@DataJpaTest
@DisplayName("DecisionSnapshotRepository")
class DecisionSnapshotRepositoryTest {

    @Autowired
    private DecisionSnapshotRepository repository;

    @Test
    @DisplayName("save puis findById renvoie l'entité persistée avec les mêmes champs")
    void save_thenFindById_returnsPersistedFields() {
        DecisionSnapshotEntity entity = new DecisionSnapshotEntity();
        entity.setDecisionId("ENTER-2026-08-13T10:00:00Z-abcdef12");
        entity.setSymbol("BTC/EUR");
        entity.setOwner("user1");
        entity.setType("ENTER");
        entity.setStatus("CREATED");
        entity.setSnapshotJson("{\"executedStepIds\":[]}");
        entity.setSnapshotAt(Instant.parse("2026-08-13T10:00:00Z"));

        repository.save(entity);

        Optional<DecisionSnapshotEntity> reloaded = repository.findById("ENTER-2026-08-13T10:00:00Z-abcdef12");

        assertTrue(reloaded.isPresent());
        assertEquals("BTC/EUR", reloaded.get().getSymbol());
        assertEquals("user1", reloaded.get().getOwner());
        assertEquals("ENTER", reloaded.get().getType());
        assertEquals("CREATED", reloaded.get().getStatus());
        assertEquals("{\"executedStepIds\":[]}", reloaded.get().getSnapshotJson());
        assertEquals(Instant.parse("2026-08-13T10:00:00Z"), reloaded.get().getSnapshotAt());
    }

    @Test
    @DisplayName("save avec le même decisionId fait un upsert (pas de doublon)")
    void save_withSameId_upserts() {
        DecisionSnapshotEntity first = new DecisionSnapshotEntity();
        first.setDecisionId("decision-1");
        first.setSymbol("BTC/EUR");
        first.setOwner("user1");
        first.setType("ENTER");
        first.setStatus("CREATED");
        first.setSnapshotJson("{}");
        first.setSnapshotAt(Instant.parse("2026-08-13T10:00:00Z"));
        repository.save(first);

        DecisionSnapshotEntity updated = new DecisionSnapshotEntity();
        updated.setDecisionId("decision-1");
        updated.setSymbol("BTC/EUR");
        updated.setOwner("user1");
        updated.setType("ENTER");
        updated.setStatus("EXECUTED");
        updated.setSnapshotJson("{\"executedStepIds\":[\"step1\"]}");
        updated.setSnapshotAt(Instant.parse("2026-08-13T11:00:00Z"));
        repository.save(updated);

        assertEquals(1, repository.count());
        assertEquals("EXECUTED", repository.findById("decision-1").orElseThrow().getStatus());
    }
}
