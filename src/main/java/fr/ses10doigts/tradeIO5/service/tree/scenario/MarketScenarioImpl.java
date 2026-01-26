package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioHistoryEntry;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.MarketAction;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MarketScenarioImpl implements MarketScenario {

    @Getter
    private final ScenarioOwner owner;
    private Optional<String> symbol;
    private final String id;
    private ScenarioState state;
    private final DomainClock clock;
    private final List<ScenarioHistoryEntry> scenarioHistory = new ArrayList<>();

    private static final double CONFIRMATION_THRESHOLD = 0.7; // pour passer EMERGING → CONFIRMING
    private static final double VALIDATION_THRESHOLD = 0.9;   // pour passer CONFIRMING → VALIDATED
    // Pour l’instant, agir = scénario validé
    private static final double ACTION_THRESHOLD = VALIDATION_THRESHOLD;

    private static final double REINFORCE_DELTA = 0.1;     // même sens
    private static final double WEAKEN_DELTA = -0.2;       // sens opposé
    private static final double NEUTRAL_DELTA = -0.1;      // voisin / léger changement

    private static final double INVALID_THRESHOLD = 0.0;   // confiance minimale
    private static final Duration EXPIRATION_IDLE = Duration.ofHours(2); // durée max sans update // TODO Parametrize


    public MarketScenarioImpl(ScenarioType scenarioType, ScenarioOwner owner, String symbol, Instant createdAt, DomainClock clock) {
        this.owner = owner;
        this.symbol = Optional.ofNullable(symbol);
        this.clock = clock;
        this.id = generateScenarioId();
        this.state = new ScenarioState(scenarioType, createdAt);
    }


    @Override
    public void observe(MarketOpinionResult opinion, ScenarioContext context) {

        if (!state.isActive()) {
            return;
        }

        // 1️⃣ Mutation (signal + confiance progressive)
        mutateScenario(
                opinion.weightedSignal(),
                opinion.conviction()
        );

        // 2️⃣ Évolution du status métier (basée sur la confiance résultante)
        double confidence = state.getConfidence();

        switch (state.getStatus()) {
            case EMERGING -> {
                if (confidence >= CONFIRMATION_THRESHOLD) {
                    state.setStatus(ScenarioStatus.CONFIRMING);
                    state.setStable(false);
                }
            }
            case CONFIRMING -> {
                if (confidence >= VALIDATION_THRESHOLD) {
                    state.setStatus(ScenarioStatus.VALIDATED);
                    state.setStable(true);
                } else if (confidence < CONFIRMATION_THRESHOLD / 2) {
                    state.setStatus(ScenarioStatus.INVALIDATED);
                    state.setStable(false);
                }
            }
            case VALIDATED -> {
                if (confidence < CONFIRMATION_THRESHOLD) {
                    state.setStatus(ScenarioStatus.CONFIRMING);
                    state.setStable(false);
                }
            }
            default -> {
                // INVALIDATED / EXPIRED : plus d'évolution
            }
        }

        // 3️⃣ Invalidation hard (sécurité logique)
        checkInvalidation();

        // 4️⃣ Expiration temporelle
        checkExpiration(context.now());
    }


    @Override
    public Optional<ActionIntent> proposeIntent() {

        if (!state.isStable()) {
            return Optional.empty();
        }

        if (state.getStatus() != ScenarioStatus.VALIDATED) {
            return Optional.empty();
        }

        if (state.getConfidence() < ACTION_THRESHOLD) {
            return Optional.empty();
        }

        MarketAction action = switch (state.getSignal()) {
            case BULLISH -> MarketAction.BUY;
            case BEARISH -> MarketAction.SELL;
            case NEUTRAL -> MarketAction.HOLD;
        };

        if (action == MarketAction.HOLD) {
            return Optional.empty();
        }

        return Optional.of(
                new ActionIntent(
                        action,
                        state.getConfidence(),
                        id,
                        "Scenario validated and stable",
                        clock.now()
                )
        );
    }

    @Override
    public void enrichFrom(MarketScenario other) {
        if(other.getSymbol().isPresent()){
            return;
        }

        ScenarioState incoming = other.getState();
        ScenarioState current = this.state;

        double newConfidence = (current.getConfidence() * 0.7 + incoming.getConfidence() * 0.3); // TODO : Parametrize

        SignalType newSignal = adjustSignal(current.getSignal(), current.getConfidence(), incoming.getSignal(), newConfidence);

        ScenarioStatus newStatus = current.getStatus().ordinal() < incoming.getStatus().ordinal()
                ? incoming.getStatus()
                : current.getStatus();

        boolean newStable = current.isStable() && incoming.isStable();

        Instant newLastUpdated = incoming.getLastUpdated().isAfter(current.getLastUpdated())
                ? incoming.getLastUpdated()
                : current.getLastUpdated();

        this.state = new ScenarioState(
                current.getId(),
                current.getScenarioType(),
                newStatus,
                newSignal,
                newConfidence,
                newStable,
                newLastUpdated,
                current.getCreatedAt()    // IMPORTANT : on conserve l’origine
        );
    }


    private void mutateScenario( SignalType newSignal, double newConfidence) {
        double delta = 0;
        ScenarioState state = getState();
        if (state.getSignal() == newSignal) {
            delta = REINFORCE_DELTA;
        } else if (state.getSignal().isOpposite(newSignal)) {
            delta = WEAKEN_DELTA;
        } else {
            delta = NEUTRAL_DELTA;
        }

        state.setConfidence(Math.min(1, Math.max(0, state.getConfidence() + delta)));
        state.setSignal(adjustSignal(state.getSignal(), state.getConfidence(), newSignal, newConfidence));
        state.setLastUpdated(Instant.now());
        logStateChange( "MUTATION", delta);
    }

    private void checkInvalidation() {
        ScenarioState state = getState();
        if (state.getConfidence() <= INVALID_THRESHOLD) {
            state.setStatus(ScenarioStatus.INVALIDATED);
            state.setStable(false);
            logStateChange( "INVALIDATION", state.getConfidence());
        }
    }

    private void checkExpiration( Instant now ) {
        ScenarioState state = getState();
        if (Duration.between(state.getLastUpdated(), now).compareTo(EXPIRATION_IDLE) > 0) {
            state.setStatus(ScenarioStatus.EXPIRED);
            state.setStable(false);
            logStateChange("EXPIRATION", Duration.between(state.getLastUpdated(), now).toMillis());
        }
    }

    private void logStateChange( String type, Object info) {
        // Stocker dans un historique dédié
        ScenarioState state = getState();
        scenarioHistory.add(new ScenarioHistoryEntry(
                getClass().getSimpleName(), // TODO
                state.getScenarioType(),
                state.getSignal(),
                state.getConfidence(),
                state.getStatus(),
                clock.now(),
                type, // TODO
                info // TODO...
        ));
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
