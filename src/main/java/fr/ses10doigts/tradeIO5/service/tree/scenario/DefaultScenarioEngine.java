package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.EngineCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.IntentCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Le moteur doit faire 3 choses seulement :
 * Créer / injecter un ScenarioEventEmitter
 * Appeler observe(...) et enrichFrom(...)
 * Stocker les scénarios vivants
 * 👉 Et c’est tout.
 */

public class DefaultScenarioEngine implements ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioEngine.class);

    private final ScenarioOwner owner;
    private final DomainClock clock;
    private final Set<String> symbols;
    private final EventBus eventBus;

    final Map<ScenarioKey, MarketScenario> scenarios = new ConcurrentHashMap<>();

    public DefaultScenarioEngine(ScenarioOwner owner, DomainClock clock, Set<String> symbols, EventBus eventBus) {
        this.owner = owner;
        this.clock = clock;
        this.symbols = symbols;
        this.eventBus = eventBus;

        eventBus.subscribe(OpinionEvent.class, this::onOpinionEvent);
    }

    public void onOpinionEvent(OpinionEvent event) {

        if( event.getSymbol().isPresent() && !symbols.contains(event.getSymbol().get()) ){
            log.debug("Event received for {} but not on survey list", event.getSymbol().get());

        }else if(event.getSymbol().isEmpty()) {
            log.debug("Global Event received");
        }

        OpinionSignal result = eventToOpinionSignal(event);
        ScenarioContext context = new ScenarioContext(
                owner,
                event.getSymbol(),
                clock,
                getGlobalScenarios(owner)
        );

        onMarketOpinion(result, context);
    }


    @Override
    public void onMarketOpinion(
            OpinionSignal opinion,
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
        List<MarketScenario> created = ScenarioFactory.create(opinion, enrichedContext, eventBus);

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

        // 5. Récupération des scenarios mûrs
        List<ActionIntent> actionIntents = collectActionIntents(owner, clock.now());

        log.debug("Owner {}: {} scenarios actifs, {} Intent(s)",
                context.owner(),
                scenarios.size(),
                actionIntents.size());
    }

    // ------------ Scenario manipulations -------------

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
        List<ActionIntent> intents = new ArrayList<>();

        for (var entry : scenarios.entrySet()) {
            if (!isVisibleForOwner(entry.getKey().owner(), owner)) {
                continue;
            }

            MarketScenario marketScenario = entry.getValue();

            // TODO : Warning... Est-ce que ca ne flooderait pas avec des Intent déjà proposées?
            Optional<ActionIntent> proposedIntent = marketScenario.proposeIntent(now);
            if (proposedIntent.isEmpty()) {
                continue;
            }

            ActionIntent intent = proposedIntent.get();

            eventBus.publish(
                    new ScenarioEvent(
                            marketScenario,
                            ScenarioEventType.ACTION_PROPOSED,
                            new IntentCause(
                                    marketScenario.getId(),
                                    intent,
                                    intent.reason()
                            ),
                            marketScenario.getState(),
                            now
                    )
            );

            intents.add(intent);
        }

        return intents;
    }

    @Override
    public void cleanup( Duration maxAge, Instant now ) {
        List<MarketScenario> toRemove = scenarios.values().stream()
                .filter(s -> !s.isActive(now, maxAge))
                .toList();

        // On retire
        toRemove.forEach(s -> scenarios.remove(keyOf(s)));

        // On émet l'événement pour chaque scénario supprimé
        toRemove.forEach(s -> eventBus.publish(
                new ScenarioEvent(
                        s,
                        ScenarioEventType.SCENARIO_EXPIRED,
                        new EngineCause(s.getId(), "Scenario removed", "No more active"),
                        s.getState(),
                        now
                )
        ));
    }

    // ---------- Symbols survey Set ----------

    public void addSymbolSurvey( String symbol ){
        symbols.add(symbol);
    }

    public void removeSymbolSurvey( String symbol ){
        symbols.remove(symbol);

        List<MarketScenario> toRemove = scenarios.values().stream()
                .filter(s -> s.getSymbol().isPresent() && s.getSymbol().get().equals(symbol))
                .toList();

        toRemove.forEach(s -> {
            MarketScenario toBeDelScenario = scenarios.get(keyOf(s));
            eventBus.publish(new ScenarioEvent(
                    s,
                    ScenarioEventType.SCENARIO_EXPIRED,
                    new EngineCause(
                            toBeDelScenario.getId(),
                            "Removing all Scenarii from symbol "+ symbol,
                            "Symbol removed from survey list: "+ symbol
                    ),
                    s.getState(),
                    clock.now()
            ));
            scenarios.remove(keyOf(s));
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

    private OpinionSignal eventToOpinionSignal( OpinionEvent event ){
        return new OpinionSignal(
                event.getOpinionId(),
                event.getSymbol(),
                event.getMajoritySignal(),
                event.getWeightedSignal(),
                event.getConfidence(),
                event.getScore(),
                event.getScope(),
                event.getSources(),
                event.getReason(),
                event.getTimestamp()
        );
    }

}

