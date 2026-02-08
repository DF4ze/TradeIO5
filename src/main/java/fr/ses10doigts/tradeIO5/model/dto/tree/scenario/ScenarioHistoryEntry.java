package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class ScenarioHistoryEntry {
    private final String scenarioId;       // ID unique du scénario
    private final ScenarioType scenarioType; // type de scénario (TRENDING_UP, RANGE, etc.)
    private final SignalType signal;       // weightedSignal actuel (BULLISH/BEARISH/NEUTRAL)
    private final double confidence;       // confiance à ce moment
    private final ScenarioStatus status;   // état du scénario
    private final Instant timestamp;       // instant de l'événement
    private final String changeType;       // MUTATION / INVALIDATION / EXPIRATION
    private final Object info;             // info complémentaire (delta, durée, etc.)

}
