package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.external;


import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FearAndGreedResponse {
    private String name;
    private FearAndGreedData now;
    private FearAndGreedData yesterday;
    private FearAndGreedData lastWeek;
    // getters / setters
}


