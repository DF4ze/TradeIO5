package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioEventType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.ScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.EngineCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioFactory;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Le moteur doit faire 3 choses seulement :
 *
 * Créer / injecter un ScenarioEventEmitter
 *
 * Appeler observe(...) et enrichFrom(...)
 *
 * Stocker les scénarios vivants
 *
 * 👉 Et c’est tout.
 */

@RequiredArgsConstructor
public class DefaultScenarioEngine implements ScenarioEngine {

    private final Logger log = LoggerFactory.getLogger(DefaultScenarioEngine.class);

    private final ScenarioEventEmitter emitter;

    /** Stockage centralisé et thread-safe */
    final Map<ScenarioKey, MarketScenario> scenarios = new ConcurrentHashMap<>();

    @Override
    public void onMarketOpinion(
            MarketOpinionResult opinion,
            ScenarioContext context
    ) {

        // 1. enrichir le contexte avec les scénarios globaux actifs
        ScenarioContext enrichedContext = context.withGlobalScenarios(getGlobalScenarios(context.owner()));

        // 2. observer les scénarios existants
        scenarios.forEach((key, scenario) -> {
            if (isVisibleForOwner(key.owner(), enrichedContext.owner())) {
                scenario.observe(opinion, enrichedContext);
            }
        });

        // 3. proposer de nouveaux scénarios
        List<MarketScenario> created = ScenarioFactory.create(opinion, enrichedContext, emitter);

        // 4. Si scenario existe, merge
        for (MarketScenario scenario : created) {
            scenarios.merge(
                    keyOf(scenario),
                    scenario,
                    (existing, incoming) -> {
                        existing.enrichFrom(incoming, context.clock().now());
                        return existing;
                    }
            );
        }

        log.debug("Owner {}: {} scenarios actifs",
                context.owner(),
                scenarios.size());
    }

    @Override
    public List<MarketScenario> getActiveScenarios(ScenarioOwner owner, Duration maxAge, Instant now) {
        return scenarios.entrySet().stream()
                .filter(e -> isVisibleForOwner(e.getKey().owner(), owner))
                .map(Map.Entry::getValue)
                .filter(s -> s.isActive(now, maxAge))
                .toList();
    }

    @Override
    public List<ActionIntent> collectActionIntents(ScenarioOwner owner, Instant now) {
        return scenarios.entrySet().stream()
                .filter(e -> isVisibleForOwner(e.getKey().owner(), owner))
                .map(Map.Entry::getValue)
                .map(marketScenario -> marketScenario.proposeIntent(now))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void cleanup( Duration maxAge, Instant now ) {
        List<MarketScenario> toRemove = scenarios.values().stream()
                .filter(s -> !s.isActive(now, maxAge))
                .toList();

        // On retire
        toRemove.forEach(s -> scenarios.remove(keyOf(s)));

        // On émet l'événement pour chaque scénario supprimé
        toRemove.forEach(s -> emitter.emit(
                new ScenarioEvent(
                        s,
                        ScenarioEventType.SCENARIO_EXPIRED,
                        new EngineCause(s.getId(), "Scenario removed", "No more active"),
                        s.getState(),
                        now
                )
        ));
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


}

