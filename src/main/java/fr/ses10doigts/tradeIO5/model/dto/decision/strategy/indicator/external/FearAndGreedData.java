package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.external;


import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FearAndGreedData {
    private int value;
    private String valueClassification;
    private long timestamp;
    private String updateTime; // facultatif (présent seulement dans "now")
    // getters / setters
}