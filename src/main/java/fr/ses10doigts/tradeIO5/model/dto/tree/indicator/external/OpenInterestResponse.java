package fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Réponse Coinalyze {@code GET /v1/open-interest} (étude "indicateurs-macro-externes", §9) :
 * {@code [{ "symbol", "value", "update" }]} par symbole demandé (le projet n'en demande qu'un à la
 * fois, mais l'API accepte une liste CSV — voir {@code CoinalyzeClient}).
 */
@Data
@NoArgsConstructor
public class OpenInterestResponse {

    private List<Entry> entries;
    private boolean valid = true;

    public static OpenInterestResponse invalid() {
        OpenInterestResponse response = new OpenInterestResponse();
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
