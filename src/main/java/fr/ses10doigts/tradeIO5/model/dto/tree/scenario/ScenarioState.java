package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;

@Data
@AllArgsConstructor
public class ScenarioState{

    private ScenarioType scenarioType;      // scenario: le scénario actif (TRENDING_UP, RANGE, CRASH, …)
    private ScenarioStatus status;          // status : état du scenario
    private SignalType signal;              // weightedSignal: BULLISH / BEARISH / NEUTRAL
    private double confidence;              // confidence: confidence globale (0–1 )
    private boolean stable;                 // stable: validé / confirmé vs transitoire
    private Instant lastUpdated;            // lastUpdate: dernière confirmation
    private Instant createdAt;              // since: début du scénario

    public ScenarioState(ScenarioType scenarioType, Instant createdAt){
        this.scenarioType = scenarioType;
        this.createdAt = createdAt;
        this.lastUpdated = createdAt;
        this.status = ScenarioStatus.INITIAL;
        this.stable = false;
        this.confidence = 0.0;
        this.signal = SignalType.NEUTRAL;
    }

    public ScenarioState(ScenarioState state, Instant now) {
        scenarioType = state.scenarioType;
        createdAt = now;
        lastUpdated = now;
        status = state.status;
        stable = state.stable;
        confidence = state.confidence;
        signal = state.signal;
    }

    public boolean isExpired(Instant now, Duration duration) {

        if (status == ScenarioStatus.INVALIDATED) {
            stable = false;
            return true;
        }

        if (status == ScenarioStatus.EXPIRED) {
            stable = false;
            return true;
        }

        if (lastUpdated.plus(duration).isBefore(now)) {
            status = ScenarioStatus.EXPIRED;
            stable = false;
            return true;
        }

        return false;
    }

    public boolean isActive(){
        return status.isActive();
    }
}
