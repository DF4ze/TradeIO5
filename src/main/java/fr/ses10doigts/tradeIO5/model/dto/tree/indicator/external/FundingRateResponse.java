package fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Réponse Coinalyze {@code GET /v1/funding-rate} (étude "indicateurs-macro-externes", §12) :
 * {@code [{ "symbol", "value", "update" }]}, même forme que {@link OpenInterestResponse} mais
 * gardée comme type distinct (sémantique différente : taux de funding, pas open interest).
 */
@Data
@NoArgsConstructor
public class FundingRateResponse {

    private List<Entry> entries;
    private boolean valid = true;

    public static FundingRateResponse invalid() {
        FundingRateResponse response = new FundingRateResponse();
        response.setValid(false);
        return response;
    }

    @Data
    @NoArgsConstructor
    public static class Entry {
        private String symbol;
        private double value;
        private long update;
    }
}
