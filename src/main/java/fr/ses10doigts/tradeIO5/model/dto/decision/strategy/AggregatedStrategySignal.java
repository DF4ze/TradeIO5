package fr.ses10doigts.tradeIO5.model.dto.decision.strategy;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Builder
@Data
public class AggregatedStrategySignal {

    private SignalType finalSignal;          // BUY / SELL / HOLD
    private double score;                    // score -1 +1
    private double confidence;               // confiance globale
    private boolean conflictDetected;        // BUY vs SELL simultanés

    private List<StrategySignal> signals;    // signaux bruts
    private String explanation;              // lisible humain
}
