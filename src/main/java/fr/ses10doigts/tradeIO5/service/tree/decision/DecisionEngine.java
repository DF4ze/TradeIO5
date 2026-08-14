package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.DecisionCreatedCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.IntentCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionCandidate;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultMarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);

    // Mutualisé (Palier 3, étape 4, retour Clem du 2026-08-13) : référence directe à
    // DefaultMarketScenario.EXPIRATION_IDLE au lieu d'une copie locale — évite le bug silencieux
    // "modifié à un endroit, pas à l'autre". Durée max pour qu'un scénario soit considéré "actif"
    // lors de l'arbitrage inter-scopes ci-dessous.
    private static final Duration MAX_SCENARIO_AGE = DefaultMarketScenario.EXPIRATION_IDLE;

    private final DomainClock clock;
    //private final Set<String> symbols;
    private final EventBus eventBus;
    private final ScenarioEngine scenarioEngine;
    private final Map<String, Decision> activeDecisions;

    public DecisionEngine(DomainClock clock, EventBus eventBus, ScenarioEngine scenarioEngine) {
        this.clock = clock;
       // this.symbols = symbols;
        this.eventBus = eventBus;
        this.scenarioEngine = scenarioEngine;
        activeDecisions = new ConcurrentHashMap<>();

        eventBus.subscribe(ScenarioEvent.class, this::onScenarioEvent);
    }

    private void onScenarioEvent(ScenarioEvent event) {

        // Palier 3, étape 1 (option B3) : ScenarioEvent porte déjà l'owner du scénario d'origine
        // (event.getOwner()) — l'auto-abonnement reste valide sous singleton, plus de filtre par
        // owner.isVisible(...) ici (cette instance doit désormais traiter les events de tous les
        // owners, tout le point de B3).
        if( event.getScenarioEventType() != ScenarioEventType.ACTION_PROPOSED ||    // Que les scenarios terminés
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
                        "On Scenario Event",
                        decision.getSteps()
                ),
                clock.now()
        ));

//        executor.execute(decision);
    }

    /**
     * Consultation d'une Decision active par id (ex: suivi côté executor/API à venir).
     * Rend {@link #activeDecisions} réellement interrogée, pas seulement alimentée.
     */
    public Optional<Decision> getActiveDecision(String decisionId) {
        return Optional.ofNullable(activeDecisions.get(decisionId));
    }

    /**
     * Toutes les Decision actives, tous owners confondus (photo quotidienne, étape 4 Palier 3).
     * "Actif" = statut CREATED, seul statut non-terminal réellement atteint aujourd'hui (EXECUTED/
     * ABORTED sont terminaux ; OBSERVING/CLOSED/EXPIRED existent dans DecisionStatus mais ne sont
     * produits par aucun chemin de code actuel — à revisiter si ça change).
     */
    public List<Decision> getAllActiveDecisions() {
        return activeDecisions.values().stream()
                .filter(d -> d.getStatus() == DecisionStatus.CREATED)
                .toList();
    }

    /**
     * Restauration au (re)démarrage (Palier 3, étape 4) : réinjecte des décisions déjà reconstruites
     * (photo + rejeu séquentiel via {@link Decision#apply}, cf. {@code DecisionScenarioRestoreRunner})
     * directement dans {@link #activeDecisions}. Clé de la map = {@code
     * decision.getSnapshot().decisionId()}, PAS {@code decision.getId()} — même convention que {@link
     * #onScenarioEvent}, à ne pas confondre (cf. décision actée en tête du prompt d'implémentation de
     * cette étape).
     */
    public void restoreDecisions(List<Decision> decisions) {
        decisions.forEach(d -> activeDecisions.put(d.getSnapshot().decisionId(), d));
    }

    /**
     * Unanimité entre tous les scénarios actifs du même symbole, tous scopes confondus
     * (LOCAL, EXTERNAL, ...). 0 ou 1 action distincte proposée = unanimité (y compris le cas
     * où un seul scope a un scénario actif pour ce symbole, cas normal aujourd'hui).
     */
    private boolean isUnanimousAcrossScopes(ScenarioEvent event) {
        // Palier 3, étape 1 : scoper la requête à l'owner de l'événement traité, jamais à un
        // champ d'instance (qui n'existe plus depuis ce lot).
        List<MarketScenario> sameSymbolScenarios = scenarioEngine
                .getActiveScenarios(event.getOwner(), MAX_SCENARIO_AGE, clock.now())
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
                candidate.type(),
                clock.now()
        );

        ActionStep step = new ActionStep(
                UUID.randomUUID().toString(),
                candidate.action(),
                candidate.quantity(),
                candidate.walletId() // circule depuis le candidate ; toujours null tant que rien ne le résout (§5/§4)
        );

        return new Decision(
                snapshot,
                List.of(step)
        );
    }

    private DecisionCandidate mapToCandidate(ScenarioEvent event) {
        ActionIntent intent = ((IntentCause)event.getCause()).intent();
        ExecutionAction action = mapAction(intent);
        return new DecisionCandidate(
                event.getSymbol().orElseThrow(() ->
                        new IllegalStateException("Decision requires a symbol")),
                mapDecisionType(action),
                action,
                intent.confidence(),
                intent.quantity(),
                intent.reason(),
                event.getOwner(),
                clock.now(),
                null // pas encore de wallet cible résolu (§5/§4, hors scope de ce lot)
        );
    }

    private ExecutionAction mapAction(ActionIntent intent) {
        return switch (intent.action()) {
            case BUY  -> ExecutionAction.BUY;
            case SELL -> ExecutionAction.SELL;
            default -> ExecutionAction.NO_OP;
        };
    }

    /**
     * ⚠️ Mapping minimal (Palier 1, 2026-08), pas la version finale de l'algorithmie décisionnelle.
     * REBALANCE/STOP sont volontairement hors scope ici : les distinguer d'un simple ENTER/EXIT
     * demanderait de connaître l'état du portefeuille (position déjà ouverte ou non), qui
     * n'existe pas encore dans ce contexte (cf. étude §12 point 7, futur composant Sizing). Ce
     * mapping est à revisiter dès que ce contexte de portefeuille existera.
     */
    static DecisionType mapDecisionType(ExecutionAction action) {
        return switch (action) {
            case BUY -> DecisionType.ENTER;
            case SELL, EXIT -> DecisionType.EXIT;
            case NO_OP -> DecisionType.EXIT; // ne devrait pas être atteint en pratique (proposeIntent n'émet jamais d'intent pour un signal NEUTRAL/HOLD) ; valeur de repli neutre, pas une vraie sémantique
        };
    }

}
