package fr.ses10doigts.tradeIO5.repository.scenario;

import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioSnapshotEntity;
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
@DisplayName("ScenarioSnapshotRepository")
class ScenarioSnapshotRepositoryTest {

    @Autowired
    private ScenarioSnapshotRepository repository;

    @Test
    @DisplayName("save puis findById renvoie l'entité persistée avec les mêmes champs")
    void save_thenFindById_returnsPersistedFields() {
        ScenarioSnapshotEntity entity = new ScenarioSnapshotEntity();
        entity.setScenarioId("scenario-1");
        entity.setScenarioType("TREND_UP");
        entity.setOwner("user1");
        entity.setSymbol("BTC");
        entity.setScope("LOCAL");
        entity.setStateJson("{\"scenarioType\":\"TREND_UP\"}");
        entity.setSnapshotAt(Instant.parse("2026-08-13T10:00:00Z"));

        repository.save(entity);

        Optional<ScenarioSnapshotEntity> reloaded = repository.findById("scenario-1");

        assertTrue(reloaded.isPresent());
        assertEquals("TREND_UP", reloaded.get().getScenarioType());
        assertEquals("user1", reloaded.get().getOwner());
        assertEquals("BTC", reloaded.get().getSymbol());
        assertEquals("LOCAL", reloaded.get().getScope());
        assertEquals("{\"scenarioType\":\"TREND_UP\"}", reloaded.get().getStateJson());
        assertEquals(Instant.parse("2026-08-13T10:00:00Z"), reloaded.get().getSnapshotAt());
    }

    @Test
    @DisplayName("save avec le même scenarioId fait un upsert (pas de doublon)")
    void save_withSameId_upserts() {
        ScenarioSnapshotEntity first = new ScenarioSnapshotEntity();
        first.setScenarioId("scenario-1");
        first.setScenarioType("TREND_UP");
        first.setOwner("user1");
        first.setScope("LOCAL");
        first.setStateJson("{}");
        first.setSnapshotAt(Instant.parse("2026-08-13T10:00:00Z"));
        repository.save(first);

        ScenarioSnapshotEntity updated = new ScenarioSnapshotEntity();
        updated.setScenarioId("scenario-1");
        updated.setScenarioType("TREND_UP");
        updated.setOwner("user1");
        updated.setScope("LOCAL");
        updated.setStateJson("{\"confidence\":0.9}");
        updated.setSnapshotAt(Instant.parse("2026-08-13T11:00:00Z"));
        repository.save(updated);

        assertEquals(1, repository.count());
        assertEquals("{\"confidence\":0.9}", repository.findById("scenario-1").orElseThrow().getStateJson());
    }
}
