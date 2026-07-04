package fr.ses10doigts.tradeIO5.model.dto.event;

import com.github.f4b6a3.ulid.UlidCreator;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Getter
@RequiredArgsConstructor
public class OpinionEvent implements PersistableEvent {
    private final String id;
    private String opinionId;
    private final EventType eventType = EventType.SCENARIO;
    private Optional<String> symbol;                 // nullable si GLOBAL/MACRO
    private OpinionScope scope;
    private SignalType majoritySignal;
    private SignalType weightedSignal;
    private double confidence;              // 0.0 → 1.0
    private double score;                   // -1.0 → 1.0
    private Set<String> sources;            // strategies/opinions agrégées
    private String reason;
    private Instant timestamp;

    public OpinionEvent(OpinionSignal opinion){
        id = "[OpinionEvent-"+UlidCreator.getUlid().toString()+"]"+opinion.opinionId();
        opinionId = opinion.opinionId();
        symbol = opinion.symbol();
        scope = opinion.scope();
        majoritySignal = opinion.majoritySignal();
        weightedSignal = opinion.weightedSignal();
        confidence = opinion.confidence();
        score = opinion.score();
        sources = opinion.sources();
        reason = opinion.reason();
        timestamp = opinion.timestamp();
    }

    @Override
    public String getTargetId() {
        return opinionId;
    }
}
