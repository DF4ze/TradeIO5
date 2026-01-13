package fr.ses10doigts.tradeIO5.model.dto.decision.strategy;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import lombok.Builder;
import lombok.Data;

import java.util.Map;


/**
 * - Pourquoi
 * Tout signal doit être contextualisé.
 *
 * - Responsabilité
 * score directionnel
 * confiance
 * horizon (court / moyen / long)
 * optionnellement : type de risque
 *
 * - Conceptuellement
 * “Je suis confiant à 70% que renforcer légèrement est pertinent.”
 */
@Data
@Builder
public class StrategySignal {

    private double score;        // -1.0 → 1.0
    private double confidence;
    private SignalType type;
    private String reason;            // log / debug

    private String strategyName;
    private boolean valid;

    private Map<String, Object> metadata;

    public static StrategySignal simple( SignalType type, double score, double confidence){
        return StrategySignal.builder()
                .type(type)
                .score(score)
                .confidence(confidence)
                .build();
    }

    public static StrategySignal notValid(String strategyName, String reason) {
        return StrategySignal.builder()
                .score(0)
                .valid(false)
                .strategyName(strategyName)
                .reason(reason)
                .build();
    }


}
