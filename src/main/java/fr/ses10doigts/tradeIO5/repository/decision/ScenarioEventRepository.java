package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScenarioEventRepository extends JpaRepository<ScenarioEventEntity, Long> {


    List<ScenarioEventEntity> findByScenarioId(String scenarioId);
}
