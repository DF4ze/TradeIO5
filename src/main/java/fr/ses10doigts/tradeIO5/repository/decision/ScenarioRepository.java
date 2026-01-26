package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<ScenarioStateEntity, Long> {
}
