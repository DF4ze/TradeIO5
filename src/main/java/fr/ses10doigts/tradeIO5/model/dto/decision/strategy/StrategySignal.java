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
 * optionalement : type de risque
 *
 * - Conceptuellement
 * “Je suis confiant à 70% que renforcer légèrement est pertinent.”
 */
@Data
@Builder
public class StrategySignal {

    private SignalType type;          // BUY / SELL / HOLD
    private double confidence;        // 0.0 → 1.0
    private String reason;            // log / debug

    private String strategyName;

    private Map<String, Object> metadata;

    public static StrategySignal notValid(String strategyName, String reason) {
        return StrategySignal.builder()
                .type(SignalType.HOLD)
                .confidence(0)
                .strategyName(strategyName)
                .reason(reason)
                .build();
    }

    public static StrategySignal hold(String strategyName, double confidence, String reason) {
        return StrategySignal.builder()
                .type(SignalType.HOLD)
                .confidence(confidence)
                .strategyName(strategyName)
                .reason(reason)
                .build();
    }

    public static StrategySignal buy(String strategyName, double confidence, String reason) {
        return StrategySignal.builder()
                .type(SignalType.BUY)
                .confidence(confidence)
                .strategyName(strategyName)
                .reason(reason)
                .build();
    }

    public static StrategySignal sell(String strategyName, double confidence, String reason) {
        return StrategySignal.builder()
                .type(SignalType.SELL)
                .confidence(confidence)
                .strategyName(strategyName)
                .reason(reason)
                .build();
    }
}
