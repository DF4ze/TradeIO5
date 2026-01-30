package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioFactory;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import fr.ses10doigts.tradeIO5.service.tree.scenario.history.ScenarioHistoryLogger;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class ScenarioEngineImpl implements ScenarioEngine {

    private final Logger log = LoggerFactory.getLogger(ScenarioEngineImpl.class);

    //private final ExecutionMode executionMode;
    private final ScenarioHistoryLogger historyLogger;
    private final ScenarioFactory scenarioFactory;

    /** Stockage centralisé et thread-safe */
    final Map<ScenarioKey, MarketScenario> scenarios = new ConcurrentHashMap<>();

    @Override
    public void onMarketOpinion(MarketOpinionResult opinion, ScenarioContext context, ExecutionMode executionMode) {

        // 1. enrichir le contexte avec les scénarios globaux actifs
        ScenarioContext enrichedContext = context.withGlobalScenarios(getGlobalScenarios(context.owner()));

        // 2. observer les scénarios existants
        scenarios.forEach((key, scenario) -> {
            if (isVisibleForOwner(key.owner(), enrichedContext.owner())) {
                ScenarioState before = scenario.getState();
                scenario.observe(opinion, enrichedContext);
                historizeIfChanged(scenario, before, executionMode);
            }
        });

        // 3. proposer de nouveaux scénarios
        List<MarketScenario> created = scenarioFactory.create(opinion, enrichedContext);

        // 4. Si scenario existe, merge
        for (MarketScenario scenario : created) {
            ScenarioKey key = keyOf(scenario);

            MarketScenario merged = scenarios.merge(
                    key,
                    scenario,
                    (existing, incoming) -> {
                        ScenarioState before = existing.getState();
                        existing.enrichFrom(incoming);
                        historizeIfChanged(existing, before, executionMode);
                        return existing;
                    }
            );

            // Si merged == scenario, c'est un scénario **nouveau**, on historise
            if (merged == scenario) {
                historizeIfChanged(scenario, null, executionMode); // signal que c'est un ajout
            }
        }

        log.debug("Owner {}: {} scenarios actifs",
                context.owner(),
                scenarios.size());
    }

    @Override
    public List<MarketScenario> getActiveScenarios(ScenarioOwner owner, Instant now, Duration maxAge) {
        return scenarios.entrySet().stream()
                .filter(e -> isVisibleForOwner(e.getKey().owner(), owner))
                .map(Map.Entry::getValue)
                .filter(s -> s.isActive(now, maxAge))
                .toList();
    }

    @Override
    public List<ActionIntent> collectActionIntents(ScenarioOwner owner) {
        return scenarios.entrySet().stream()
                .filter(e -> isVisibleForOwner(e.getKey().owner(), owner))
                .map(Map.Entry::getValue)
                .map(MarketScenario::proposeIntent)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void cleanup(Instant now, Duration maxAge, ExecutionMode executionMode) {
        scenarios.entrySet().removeIf(entry -> {
            MarketScenario s = entry.getValue();
            if (!s.isActive(now, maxAge)) {
                historyLogger.logChange(s, executionMode);
                return true;
            }
            return false;
        });
    }

    // ---------- helpers ----------

    ScenarioKey keyOf(MarketScenario s) {
        return new ScenarioKey(
                s.getOwner(),
                s.getType(),
                s.getSymbol()
        );
    }

    private boolean isVisibleForOwner(ScenarioOwner scenarioOwner, ScenarioOwner requester) {
        if (scenarioOwner instanceof ScenarioOwner.SystemOwner) return true;
        return scenarioOwner.equals(requester);
    }

    private List<MarketScenario> getGlobalScenarios(ScenarioOwner owner) {
        return scenarios.entrySet().stream()
                .filter(e -> e.getKey().symbol().isEmpty())
                .filter(e -> isVisibleForOwner(e.getKey().owner(), owner))
                .map(Map.Entry::getValue)
                .toList();
    }

    private void historizeIfChanged(MarketScenario scenario, ScenarioState before, ExecutionMode executionMode) {
        ScenarioState after = scenario.getState();

        if (before == null || !before.equals(after)) {
            historyLogger.logChange(scenario, executionMode);
        }
    }
}

