package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.DecisionCreatedCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.IntentCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionCandidate;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);

    // Doit rester cohérent avec DefaultMarketScenario.EXPIRATION_IDLE (2h) : durée max
    // pour qu'un scénario soit considéré "actif" lors de l'arbitrage inter-scopes ci-dessous.
    private static final Duration MAX_SCENARIO_AGE = Duration.ofHours(2);

    private final ScenarioOwner owner;
    private final DomainClock clock;
    //private final Set<String> symbols;
    private final EventBus eventBus;
    private final ScenarioEngine scenarioEngine;
    private final Map<String, Decision> activeDecisions;

    public DecisionEngine(ScenarioOwner owner, DomainClock clock, EventBus eventBus, ScenarioEngine scenarioEngine) {
        this.owner = owner;
        this.clock = clock;
       // this.symbols = symbols;
        this.eventBus = eventBus;
        this.scenarioEngine = scenarioEngine;
        activeDecisions = new ConcurrentHashMap();

        eventBus.subscribe(ScenarioEvent.class, this::onScenarioEvent);
    }

    private void onScenarioEvent(ScenarioEvent event) {

        if( !owner.isVisible(event.getOwner()) ||                                       // Que pour notre owner
                event.getScenarioEventType() != ScenarioEventType.ACTION_PROPOSED ||    // Que les scenarios terminés
                event.getSymbol().isEmpty()                                             // En sécurité : pas de Globaux
        ) return;

        // Arbitrage LOCAL/EXTERNAL/... : depuis que le scope fait partie de ScenarioKey
        // (voir étude "extension-risk-macro-external"), deux opinions de scopes différents
        // sur le même symbole peuvent coexister comme deux scénarios distincts plutôt que se
        // recouvrir silencieusement. Règle volontairement la plus simple possible : on ne crée
        // une Decision que si tous les scénarios actifs de ce symbole (tous scopes confondus)
        // proposent la même action ; en cas de désaccord, on ne fait rien (pas de Decision).
        if (!isUnanimousAcrossScopes(event)) {
            log.debug("Divergence entre scopes pour le symbole {} : aucune Decision créée", event.getSymbol());
            return;
        }

        DecisionCandidate candidate = mapToCandidate(event);

        Decision decision = createDecision(candidate);
        activeDecisions.put(decision.getSnapshot().decisionId(), decision);

        eventBus.publish(new DecisionEvent(
                decision,
                DecisionEventType.DECISION_CREATED,
                new DecisionCreatedCause(
                        decision.getId(),
                        "On Scenario Event"
                ),
                clock.now()
        ));

//        executor.execute(decision);
    }

    /**
     * Unanimité entre tous les scénarios actifs du même symbole, tous scopes confondus
     * (LOCAL, EXTERNAL, ...). 0 ou 1 action distincte proposée = unanimité (y compris le cas
     * où un seul scope a un scénario actif pour ce symbole, cas normal aujourd'hui).
     */
    private boolean isUnanimousAcrossScopes(ScenarioEvent event) {
        List<MarketScenario> sameSymbolScenarios = scenarioEngine
                .getActiveScenarios(owner, MAX_SCENARIO_AGE, clock.now())
                .stream()
                .filter(s -> s.getSymbol().equals(event.getSymbol()))
                .toList();

        Set<MarketIntentAction> proposedActions = sameSymbolScenarios.stream()
                .map(s -> s.proposeIntent(clock.now()))
                .flatMap(Optional::stream)
                .map(ActionIntent::action)
                .collect(Collectors.toSet());

        return proposedActions.size() <= 1;
    }


    public Decision createDecision(DecisionCandidate candidate) {

        DecisionSnapshot snapshot = new DecisionSnapshot(
                UUID.randomUUID().toString(),
                candidate.symbol(),
                candidate.owner(),
                DecisionType.EXIT,
                clock.now()
        );

        ActionStep step = new ActionStep(
                UUID.randomUUID().toString(),
                candidate.action(),
                candidate.quantity()
        );

        return new Decision(
                snapshot,
                List.of(step),
                eventBus
        );
    }

    private DecisionCandidate mapToCandidate(ScenarioEvent event) {
        ActionIntent intent = ((IntentCause)event.getCause()).intent();
        return new DecisionCandidate(
                event.getSymbol().orElseThrow(() ->
                        new IllegalStateException("Decision requires a symbol")),
                DecisionType.EXIT, // TODO
                mapAction(intent),
                intent.confidence(),
                intent.quantity(),
                intent.reason(),
                event.getOwner(),
                clock.now()
        );
    }

    private ExecutionAction mapAction(ActionIntent intent) {
        return switch (intent.action()) {
            case BUY  -> ExecutionAction.BUY;
            case SELL -> ExecutionAction.SELL;
            default -> ExecutionAction.NO_OP;
        };
    }

}
