package fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external;


import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FearAndGreedResponse {
    private String name;
    private FearAndGreedData now;
    private FearAndGreedData yesterday;
    private FearAndGreedData lastWeek;
    private boolean valid = true;

    public static FearAndGreedResponse invalid() {
        FearAndGreedResponse response = new FearAndGreedResponse();
        response.setValid(false);
        return response;
    }
}


