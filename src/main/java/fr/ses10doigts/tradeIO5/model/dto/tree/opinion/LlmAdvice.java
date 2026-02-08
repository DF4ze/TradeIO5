package fr.ses10doigts.tradeIO5.model.dto.tree.opinion;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LlmAdvice {

    private MarketIntentAction action;  // BUY / SELL / HOLD
    private double confidence;      // 0..1
    private String rationale;       // optionnel
    private boolean valid;

    public static LlmAdvice invalid(){
        return new LlmAdvice(MarketIntentAction.HOLD, 0.0, "error see logs", false);
    }
}