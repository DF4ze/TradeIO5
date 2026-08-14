package fr.ses10doigts.tradeIO5.service.tree.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshotPayload;
import fr.ses10doigts.tradeIO5.model.entity.tree.decision.DecisionSnapshotEntity;
import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioSnapshotEntity;
import fr.ses10doigts.tradeIO5.repository.decision.DecisionSnapshotRepository;
import fr.ses10doigts.tradeIO5.repository.scenario.ScenarioSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultMarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Photo quotidienne de l'état actif de {@link ScenarioEngine}/{@link DecisionEngine} (Palier 3,
 * étape 4, docs/etudes/etude-branchement-persistance-decision-engine.md §C/§E pt3). Upsert dans
 * {@code scenario_snapshots}/{@code decision_snapshots} — pas d'historique de photos accumulées
 * (déjà couvert par le log d'événements existant). Déclenché par {@link
 * fr.ses10doigts.tradeIO5.service.scheduler.DecisionScenarioSnapshotJob} (désactivé par défaut) ou
 * par l'endpoint admin (étape 7).
 */
@Service
@RequiredArgsConstructor
public class DecisionScenarioSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DecisionScenarioSnapshotService.class);

    // Mutualisé (Palier 3, étape 4, retour Clem du 2026-08-13) : référence directe à
    // DefaultMarketScenario.EXPIRATION_IDLE au lieu d'une 3e copie locale.
    private static final Duration MAX_SCENARIO_AGE = DefaultMarketScenario.EXPIRATION_IDLE;

    private final ScenarioEngine scenarioEngine;
    private final DecisionEngine decisionEngine;
    private final ScenarioSnapshotRepository scenarioSnapshotRepository;
    private final DecisionSnapshotRepository decisionSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final DomainClock clock;

    public SnapshotResult takeSnapshot() {
        Instant now = clock.now();
        List<MarketScenario> scenarios = scenarioEngine.getAllActiveScenarios(MAX_SCENARIO_AGE, now);
        List<Decision> decisions = decisionEngine.getAllActiveDecisions();

        scenarios.forEach(scenario -> scenarioSnapshotRepository.save(toEntity(scenario, now)));
        decisions.forEach(decision -> decisionSnapshotRepository.save(toEntity(decision, now)));

        log.info("DecisionScenarioSnapshotService: photo prise ({} scénario(s), {} décision(s)).",
                scenarios.size(), decisions.size());

        return new SnapshotResult(scenarios.size(), decisions.size(), now);
    }

    private ScenarioSnapshotEntity toEntity(MarketScenario scenario, Instant now) {
        ScenarioSnapshotEntity entity = new ScenarioSnapshotEntity();
        entity.setScenarioId(scenario.getId());
        entity.setScenarioType(scenario.getType().name());
        entity.setOwner(scenario.getOwner().getId());
        entity.setSymbol(scenario.getSymbol().orElse(null));
        entity.setScope(scenario.getScope().name());
        entity.setStateJson(writeJson(scenario.getState()));
        entity.setSnapshotAt(now);
        return entity;
    }

    private DecisionSnapshotEntity toEntity(Decision decision, Instant now) {
        DecisionSnapshotEntity entity = new DecisionSnapshotEntity();
        entity.setDecisionId(decision.getId());
        entity.setSymbol(decision.getSymbol());
        entity.setOwner(decision.getOwner().getId());
        entity.setType(decision.getType().name());
        entity.setStatus(decision.getStatus().name());
        entity.setSnapshotJson(writeJson(new DecisionSnapshotPayload(
                decision.getSnapshot(),
                decision.getSteps(),
                decision.getExecutedStepIds(),
                decision.getLastUpdatedAt()
        )));
        entity.setSnapshotAt(now);
        return entity;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("DecisionScenarioSnapshotService: échec de sérialisation d'une photo : {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to serialize snapshot payload", e);
        }
    }
}
