package fr.ses10doigts.tradeIO5.service.converter;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioStateEntity;

public class ScenarioStateMapper {

    public static ScenarioStateEntity toEntity(/*String userId,*/ ScenarioState state) {
        return new ScenarioStateEntity(
                state.getId(),
                state.getScenarioType(),
                state.getStatus(),
                state.getSignal(),
                state.getConfidence(),
                state.isStable(),
                state.getLastUpdated(),
                state.getCreatedAt()
                //userId
        );
    }

    public static ScenarioState toDto(ScenarioStateEntity entity) {
        return new ScenarioState(
                entity.getId(),
                entity.getScenario(),
                entity.getStatus(),
                entity.getSignal(),
                entity.getConfidence(),
                entity.isStable(),
                entity.getLastUpdated(),
                entity.getCreatedAt()
        );
    }
}

