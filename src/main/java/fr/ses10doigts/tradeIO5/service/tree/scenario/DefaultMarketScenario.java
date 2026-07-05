package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.EnrichmentCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.InvalidityCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.OpinionCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.TimeCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class DefaultMarketScenario implements MarketScenario {

    private final Logger logger = LoggerFactory.getLogger(DefaultMarketScenario.class);

    private static final double CONFIRMATION_THRESHOLD = 0.7; // pour passer EMERGING → CONFIRMING
    private static final double VALIDATION_THRESHOLD = 0.9;   // pour passer CONFIRMING → VALIDATED
    // Pour l’instant, agir = scénario validé
    private static final double ACTION_THRESHOLD = VALIDATION_THRESHOLD;

    private static final double REINFORCE_DELTA = 0.1;     // même sens
    private static final double WEAKEN_DELTA = -0.2;       // sens opposé
    private static final double NEUTRAL_DELTA = -0.1;      // voisin / léger changement

    private static final double INVALID_THRESHOLD = 0.0;   // confiance minimale
    private static final Duration EXPIRATION_IDLE = Duration.ofHours(2); // durée max sans update // TODO Parametrize

    private final ScenarioOwner owner;
    private final Optional<String> symbol;
    private final OpinionScope scope;
    private final String id;
    private ScenarioState state;
    private final EventBus eventBus;

    public DefaultMarketScenario(
            ScenarioDefinition definition,
            EventBus eventBus
    ) {
        this.owner = definition.owner();
        this.symbol = definition.symbol();
        this.scope = definition.scope();
        this.state = new ScenarioState(definition.type(), definition.createdAt());
        this.id = generateScenarioId();
        this.eventBus = eventBus;
    }




    @Override
    public void observe(OpinionSignal opinion, ScenarioContext context) {

        if (!state.isActive()) {
            logger.debug("Observe : State isn't active : return");
            return;
        }

        // 1️⃣ Mutation (weightedSignal + confiance progressive)
        mutateScenario(opinion, context.clock().now());

        // 2️⃣ Évolution du status métier (basée sur la confiance résultante)
        double confidence = state.getConfidence();

        switch (state.getStatus()) {
            case INITIAL -> {
                logger.debug("INITIAL => EMERGING <{}>", confidence);
                state.setStatus(ScenarioStatus.EMERGING);
                state.setStable(false);
            }
            case EMERGING -> {
                if (confidence >= CONFIRMATION_THRESHOLD) {
                    logger.debug("EMERGING => CONFIRMING (confidence <{}>)", confidence);
                    state.setStatus(ScenarioStatus.CONFIRMING);
                    state.setStable(false);
                }else{
                    logger.debug("EMERGING but new confidence not high enough <{}> to grow more (< {})",confidence, CONFIRMATION_THRESHOLD);
                }
            }
            case CONFIRMING -> {
                if (confidence >= VALIDATION_THRESHOLD) {
                    logger.debug("CONFIRMING, confidence <{}> > Validation <{}> : stable)", confidence, VALIDATION_THRESHOLD);
                    state.setStatus(ScenarioStatus.VALIDATED);
                    state.setStable(true);

                } else if (confidence < CONFIRMATION_THRESHOLD / 2) {
                    logger.debug("CONFIRMING, confidence <{}> < Confirmation/2 <{}> => INVALIDATED)", confidence, CONFIRMATION_THRESHOLD);
                    state.setStatus(ScenarioStatus.INVALIDATED);
                    state.setStable(false);
                }
            }
            case VALIDATED -> {
                if (confidence < CONFIRMATION_THRESHOLD) {
                    logger.debug("VALIDATED but confidence <{}> < Confirmation <{}> => downgrade CONFIRMING)", confidence, CONFIRMATION_THRESHOLD);
                    state.setStatus(ScenarioStatus.CONFIRMING);
                    state.setStable(false);
                }
            }
            default -> {
                // INVALIDATED / EXPIRED : plus d'évolution
            }
        }

        // 3️⃣ Invalidation hard (sécurité logique)
        checkInvalidation(context.clock().now());

        // 4️⃣ Expiration temporelle
        checkExpiration(context.clock().now());

        state.setLastUpdated(context.clock().now());
    }


    @Override
    public Optional<ActionIntent> proposeIntent(Instant now) {

        if( symbol.isEmpty() ){
            logger.debug("Global scenario : no ActionIntent");
            return Optional.empty();
        }

        if (!state.isStable()) {
            logger.debug("Not stable: no intent");
            return Optional.empty();
        }

        if (state.getStatus() != ScenarioStatus.VALIDATED) {
            logger.debug("Stable but not validated: no intent");
            return Optional.empty();
        }

        if (state.getConfidence() < ACTION_THRESHOLD) {
            logger.debug("Validated but confidence too low: no intent (normally this log would never show...! filtered by state evolution)");
            return Optional.empty();
        }

        MarketIntentAction action = switch (state.getSignal()) {
            case BULLISH -> MarketIntentAction.BUY;
            case BEARISH -> MarketIntentAction.SELL;
            case NEUTRAL -> MarketIntentAction.HOLD;
        };

        if (action == MarketIntentAction.HOLD) {
            logger.debug("Stable, Validated but HOLD : no intent");
            return Optional.empty();
        }

        logger.debug("Stable, Validated : Building intent <{}>", action);
        return Optional.of(
                new ActionIntent(
                        action,
                        symbol.get(),
                        new BigDecimal(0.0), // TODO :  Manage quantity!
                        state.getConfidence(),
                        id,
                        "["+id+"] - Scenario validated and stable",
                        now
                )
        );
    }

    @Override
    public void enrichFrom(MarketScenario other, Instant now) {
        if(other.getSymbol().isPresent()){
            logger.debug("Enrichment only possible with GLOBAL scenario");
            return;
        }

        ScenarioState incoming = other.getState();
        ScenarioState current = this.state;
        logger.debug("Starting enrichment, base {}, other {} ", current, incoming);

        double newConfidence = (current.getConfidence() * 0.7 + incoming.getConfidence() * 0.3); // TODO : Parametrize

        SignalType newSignal = adjustSignal(current.getSignal(), current.getConfidence(), incoming.getSignal(), newConfidence);

        ScenarioStatus newStatus = current.getStatus().ordinal() < incoming.getStatus().ordinal()
                ? incoming.getStatus()
                : current.getStatus();

        boolean newStable = current.isStable() && incoming.isStable();

        Instant newLastUpdated = incoming.getLastUpdated().isAfter(current.getLastUpdated())
                ? incoming.getLastUpdated()
                : current.getLastUpdated();
        // TODO : Ou state.setLastUpdated(clock.now()); ?

        ScenarioState before = new ScenarioState(state, state.getCreatedAt());
        state = new ScenarioState(
                current.getScenarioType(),
                newStatus,
                newSignal,
                newConfidence,
                newStable,
                newLastUpdated,
                current.getCreatedAt()    // IMPORTANT : on conserve l’origine
        );


        eventBus.publish( new ScenarioEvent(
                this,
                ScenarioEventType.SCENARIO_ENRICHED,
                new EnrichmentCause(
                    other.getId(),
                    other.getState()
                ),
                before,
                now
        ));
        logger.debug("Enrichment result : {}", state);
    }

    private void mutateScenario(OpinionSignal opinion, Instant now) {
        OpinionCause cause = new OpinionCause(
                opinion.opinionId(),
                opinion.weightedSignal(),
                opinion.confidence()
        );
        ScenarioState before = new ScenarioState(state, state.getCreatedAt());

        if( state.getStatus() == ScenarioStatus.INITIAL ){
            state.setConfidence(opinion.confidence());
            state.setSignal(opinion.weightedSignal());
            eventBus.publish( new ScenarioEvent(
                    this,
                    ScenarioEventType.SCENARIO_CREATED,
                    cause,
                    before,
                    now
            ));

        }else {
            double delta = 0;
            if (state.getSignal() == opinion.weightedSignal()) {
                logger.debug("Mutation ++");
                delta = REINFORCE_DELTA;
            } else if (state.getSignal().isOpposite(opinion.weightedSignal())) {
                logger.debug("Mutation --");
                delta = WEAKEN_DELTA;
            } else {
                logger.debug("Mutation neutral");
                delta = NEUTRAL_DELTA;
            }

            state.setConfidence(Math.min(1, Math.max(0, state.getConfidence() + delta)));
            state.setSignal(adjustSignal(
                    state.getSignal(), state.getConfidence(),
                    opinion.weightedSignal(), opinion.confidence()
            ));

            eventBus.publish(new ScenarioEvent(
                    this,
                    ScenarioEventType.STATE_MUTATED,
                    cause,
                    before,
                    now
            ));
            logger.debug("Mutate result : {}", state);
        }
    }

    private void checkInvalidation( Instant now ) {
        ScenarioState before = new ScenarioState(state, state.getCreatedAt());

        if (state.getConfidence() <= INVALID_THRESHOLD) {
            state.setStatus(ScenarioStatus.INVALIDATED);
            state.setStable(false);

            eventBus.publish(new ScenarioEvent(
                    this,
                    ScenarioEventType.SCENARIO_INVALIDATED,
                    new InvalidityCause( INVALID_THRESHOLD ),
                    before,
                    now
            ));
        }

        logger.debug("Invalidation result : {}", state);
    }

    private void checkExpiration( Instant now ) {
        ScenarioState before = new ScenarioState(state, state.getCreatedAt());

        if (Duration.between(state.getLastUpdated(), now).compareTo(EXPIRATION_IDLE) > 0) {
            state.setStatus(ScenarioStatus.EXPIRED);
            state.setStable(false);

            eventBus.publish(new ScenarioEvent(
                    this,
                    ScenarioEventType.SCENARIO_EXPIRED,
                    new TimeCause(
                            state.getLastUpdated(),
                            now,
                            EXPIRATION_IDLE
                    ),
                    before,
                    now
            ));
        }
        logger.debug("checkExpiration {} : LastUpdated: {} - ContextDate: {} - max Duration: {}",
                state.getStatus() == ScenarioStatus.EXPIRED ? "KO" : "OK",
                state.getLastUpdated(),
                now,
                EXPIRATION_IDLE);
    }



    /**
     * Ajuste le weightedSignal d'un scénario selon le nouveau weightedSignal observé et les niveaux de confiance.
     *
     * @param currentSignal   Signal actuel du scénario
     * @param currentConfidence  Confiance actuelle du scénario (0-1)
     * @param incomingSignal  Nouveau weightedSignal observé
     * @param incomingConfidence Confiance de la nouvelle opinion (0-1)
     * @return SignalType ajusté
     */
    private SignalType adjustSignal(
            SignalType currentSignal,
            double currentConfidence,
            SignalType incomingSignal,
            double incomingConfidence
    ) {
        // Même direction
        if (currentSignal == incomingSignal) {
            // On renforce seulement si incoming est plus confiant
            if (incomingConfidence > currentConfidence) {
                return currentSignal; // renforcement implicite via la confiance dans mutateScenario
            } else {
                return currentSignal; // pas de changement
            }
        }

        // Sens opposé → affaiblir ou neutraliser
        if (currentSignal.isOpposite(incomingSignal)) {
            // Si l’incoming est plus confiant, on passe à NEUTRAL ou même au weightedSignal opposé
            if (incomingConfidence > currentConfidence) {
                return SignalType.NEUTRAL; // ou incomingSignal si tu veux être plus agressif
            } else {
                return currentSignal; // on reste
            }
        }

        // Cas voisin (BULLISH ↔ NEUTRAL ou BEARISH ↔ NEUTRAL)
        if (currentSignal == SignalType.NEUTRAL || incomingSignal == SignalType.NEUTRAL) {
            // On adopte le weightedSignal le plus confiant
            return currentConfidence >= incomingConfidence ? currentSignal : incomingSignal;
        }

        // Sécurité : par défaut on garde le weightedSignal actuel
        return currentSignal;
    }

    private String generateScenarioId(){
        return "%s-%s-%s".formatted(
                state.getScenarioType(),
                state.getCreatedAt(),
                UUID.randomUUID().toString().substring(0, 8)
        );
    }

    @Override
    public ScenarioState getState() {
        return state;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ScenarioOwner getOwner(){
        return owner;
    }

    @Override
    public Optional<String> getSymbol(){
        return symbol;
    }

    @Override
    public OpinionScope getScope(){
        return scope;
    }

    @Override
    public ScenarioType getType(){
        return state.getScenarioType();
    }
}
