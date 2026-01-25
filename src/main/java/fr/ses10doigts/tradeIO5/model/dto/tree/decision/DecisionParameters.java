package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.RiskProfile;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Paramètres génériques transmis à une Decision.
 * Peuvent être enrichis ou surchargés par le user.
 */
@Data
@Builder
public class DecisionParameters {

    /** Niveau de risque souhaité par l'utilisateur */
    private RiskProfile riskProfile;

    /** Activation explicite de certaines décisions */
    private Map<String, Boolean> enabledDecisions;

    /** Paramètres libres par décision */
    private Map<String, Object> customParameters;

    /** Paramèters propres aux Strategies */
    private List<StrategyKey> strategies;

    public boolean isEnabled(String name) {
        return enabledDecisions == null
                || enabledDecisions.getOrDefault(name, true);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        return customParameters == null
                ? defaultValue
                : (T) customParameters.getOrDefault(key, defaultValue);
    }
}
