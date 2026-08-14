package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.tree.decision.DecisionSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Palier 3, étape 4. {@code save(...)} sert déjà d'upsert (PK = {@code decisionId}), pas de requête
 * dérivée supplémentaire nécessaire pour ce lot (restauration = {@code findAll()}).
 */
public interface DecisionSnapshotRepository extends JpaRepository<DecisionSnapshotEntity, String> {
}
