package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.*;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.*;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.ScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.EnrichmentCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.InvalidityCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.OpinionCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.TimeCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class DefaultMarketScenario implements MarketScenario {

    private final Logger logger = LoggerFactory.getLogger(DefaultMarketScenario.class);

    private final ScenarioOwner owner;
    private final Optional<String> symbol;
    private final String id;
    private ScenarioState state;
    private final ScenarioEventEmitter emitter;

    private static final double CONFIRMATION_THRESHOLD = 0.7; // pour passer EMERGING → CONFIRMING
    private static final double VALIDATION_THRESHOLD = 0.9;   // pour passer CONFIRMING → VALIDATED
    // Pour l’instant, agir = scénario validé
    private static final double ACTION_THRESHOLD = VALIDATION_THRESHOLD;

    private static final double REINFORCE_DELTA = 0.1;     // même sens
    private static final double WEAKEN_DELTA = -0.2;       // sens opposé
    private static final double NEUTRAL_DELTA = -0.1;      // voisin / léger changement

    private static final double INVALID_THRESHOLD = 0.0;   // confiance minimale
    private static final Duration EXPIRATION_IDLE = Duration.ofHours(2); // durée max sans update // TODO Parametrize


    public DefaultMarketScenario(
            ScenarioDefinition definition,
            ScenarioEventEmitter eventEmitter
    ) {
        this.owner = definition.owner();
        this.symbol = definition.symbol();
        this.state = new ScenarioState(definition.type(), definition.createdAt());
        this.id = generateScenarioId();
        this.emitter = eventEmitter;
    }


    @Override
    public void observe(MarketOpinionResult opinion, ScenarioContext context) {

        if (!state.isActive()) {
            logger.debug("Observe : State isn't active : return");
            return;
        }

        // 1️⃣ Mutation (signal + confiance progressive)
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

        MarketAction action = switch (state.getSignal()) {
            case BULLISH -> MarketAction.BUY;
            case BEARISH -> MarketAction.SELL;
            case NEUTRAL -> MarketAction.HOLD;
        };

        if (action == MarketAction.HOLD) {
            logger.debug("Stable, Validated but HOLD : no intent");
            return Optional.empty();
        }

        logger.debug("Stable, Validated : Building intent <{}>", action);
        return Optional.of(
                new ActionIntent(
                        action,
                        state.getConfidence(),
                        id,
                        "Scenario validated and stable",
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

        emitter.emit(new ScenarioEvent(
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

    private void mutateScenario(MarketOpinionResult opinion, Instant now) {
        OpinionCause cause = new OpinionCause(
                opinion.opinionId(),
                opinion.weightedSignal(),
                opinion.conviction()
        );
        ScenarioState before = new ScenarioState(state, state.getCreatedAt());

        if( state.getStatus() == ScenarioStatus.INITIAL ){
            state.setConfidence(opinion.conviction());
            state.setSignal(opinion.weightedSignal());
            emitter.emit(new ScenarioEvent(
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
                    opinion.weightedSignal(), opinion.conviction()
            ));

            emitter.emit(new ScenarioEvent(
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

            emitter.emit(new ScenarioEvent(
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

            emitter.emit(new ScenarioEvent(
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
     * Ajuste le signal d'un scénario selon le nouveau signal observé et les niveaux de confiance.
     *
     * @param currentSignal   Signal actuel du scénario
     * @param currentConfidence  Confiance actuelle du scénario (0-1)
     * @param incomingSignal  Nouveau signal observé
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
            // Si l’incoming est plus confiant, on passe à NEUTRAL ou même au signal opposé
            if (incomingConfidence > currentConfidence) {
                return SignalType.NEUTRAL; // ou incomingSignal si tu veux être plus agressif
            } else {
                return currentSignal; // on reste
            }
        }

        // Cas voisin (BULLISH ↔ NEUTRAL ou BEARISH ↔ NEUTRAL)
        if (currentSignal == SignalType.NEUTRAL || incomingSignal == SignalType.NEUTRAL) {
            // On adopte le signal le plus confiant
            return currentConfidence >= incomingConfidence ? currentSignal : incomingSignal;
        }

        // Sécurité : par défaut on garde le signal actuel
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
    public ScenarioType getType(){
        return state.getScenarioType();
    }
}
