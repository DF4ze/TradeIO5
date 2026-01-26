package fr.ses10doigts.tradeIO5.service.tree.scenario.history;

import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioStateEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import fr.ses10doigts.tradeIO5.repository.decision.ScenarioRepository;
import fr.ses10doigts.tradeIO5.service.converter.ScenarioStateMapper;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScenarioHistoryLoggerImpl implements ScenarioHistoryLogger {

    private final Logger log = LoggerFactory.getLogger(ScenarioHistoryLoggerImpl.class);
    private final ScenarioRepository repository;


    @Override
    public void logChange(MarketScenario scenario, ExecutionMode mode) {
        switch (mode) {
            case LIVE -> {
                // Persistance DB
                ScenarioStateEntity entity = ScenarioStateMapper.toEntity(scenario.getState());
                repository.save(entity);
            }
            case BACKTEST, DEV -> {
                // Log console pour debug
                log.info("[{}] Scenario change: {}", mode, scenario.getState());
            }
        }
    }
}
