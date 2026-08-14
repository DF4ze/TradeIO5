package fr.ses10doigts.tradeIO5.repository.scenario;

import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Palier 3, étape 4. {@code save(...)} sert déjà d'upsert (PK = {@code scenarioId}), pas de requête
 * dérivée supplémentaire nécessaire pour ce lot (restauration = {@code findAll()}).
 */
public interface ScenarioSnapshotRepository extends JpaRepository<ScenarioSnapshotEntity, String> {
}
