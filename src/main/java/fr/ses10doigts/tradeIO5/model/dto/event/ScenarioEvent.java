package fr.ses10doigts.tradeIO5.model.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.ScenarioEventCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import lombok.AccessLevel;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

/**
 * Palier 3, étape 4 : {@code @JsonCreator} ajouté (même correctif/découverte que
 * {@code DecisionEvent}, cf. sa javadoc — bug préexistant, pas introduit par ce lot, mais bloquant
 * pour le respecter).
 */
@Getter
public class ScenarioEvent implements PersistableEvent {
    private final String id;
    private final String scenarioId;
    private final EventType eventType = EventType.SCENARIO;
    private final ScenarioType scenarioType;
    private final ScenarioOwner owner;
    // Nullable en interne (pas Optional<String>, anti-pattern comme type de champ) ; exposé en
    // Optional<String> via getSymbol() ci-dessous (getter manuel, @Getter(NONE) empêche Lombok d'en
    // générer un second qui retournerait le String brut). Le constructeur public continue d'accepter
    // un Optional<String> tel quel : transparent pour Jackson/Jdk8Module (sérialise Optional<String>
    // exactement comme une valeur nullable), donc aucun impact sur les ScenarioEvent déjà persistés.
    @Getter(AccessLevel.NONE)
    private final String symbol;
    // Palier 3, étape 3 : nullable, sans migration des lignes déjà persistées dans scenario_events
    // (décision actée avec Clem, cf. prompt d'implémentation) — permet à un futur rejeu de
    // reconstruire un ScenarioKey fidèle (qui inclut déjà ce scope).
    private final OpinionScope scope;

    private final ScenarioEventType scenarioEventType;
    private final ScenarioEventCause cause;

    private final ScenarioState before;
    private final ScenarioState after;

    private final Instant timestamp;

    @JsonCreator
    public ScenarioEvent(
            String id, String scenarioId, ScenarioType scenarioType, ScenarioOwner owner,
            Optional<String> symbol, OpinionScope scope, ScenarioEventType scenarioEventType,
            ScenarioEventCause cause, ScenarioState before, ScenarioState after, Instant timestamp
    ) {
        this.id = id;
        this.scenarioId = scenarioId;
        this.scenarioType = scenarioType;
        this.owner = owner;
        this.symbol = symbol.orElse(null);
        this.scope = scope;
        this.scenarioEventType = scenarioEventType;
        this.cause = cause;
        this.before = before;
        this.after = after;
        this.timestamp = timestamp;
    }

    public ScenarioEvent(MarketScenario scenario, ScenarioEventType scenarioEventType, ScenarioEventCause cause, ScenarioState before, Instant now){
        id = "[ScenarioEvent]"+scenario.getId();
        scenarioId = scenario.getId();
        scenarioType = scenario.getType();
        owner = scenario.getOwner();
        symbol = scenario.getSymbol().orElse(null);
        scope = scenario.getScope();
        after = scenario.getState();
        timestamp = now;

        this.scenarioEventType = scenarioEventType;
        this.cause = cause;
        this.before = before;
    }

    public Optional<String> getSymbol() {
        return Optional.ofNullable(symbol);
    }

    @Override
    public String getTargetId() {
        return scenarioId;
    }
}
