package fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Réponse Coinalyze {@code GET /v1/liquidation-history} (étude "indicateurs-macro-externes", §6b) :
 * {@code [{ "symbol", "history": [{ "t", "l", "s" }] }]} — {@code l}/{@code s} = volumes liquidés
 * long/short sur chaque point de la période demandée. Il n'existe pas d'endpoint "liquidation
 * courante" côté Coinalyze (contrairement à OI/funding) : {@code LiquidationsIndicator} somme ces
 * points sur une fenêtre glissante récente (voir {@code LiquidationsIndicator.P_WINDOW_HOURS}).
 */
@Data
@NoArgsConstructor
public class LiquidationHistoryResponse {

    private List<Entry> entries;
    private boolean valid = true;

    public static LiquidationHistoryResponse invalid() {
        LiquidationHistoryResponse response = new LiquidationHistoryResponse();
        response.setValid(false);
        return response;
    }

    @Data
    @NoArgsConstructor
    public static class Entry {
        private String symbol;
        private List<HistoryPoint> history;
    }

    @Data
    @NoArgsConstructor
    public static class HistoryPoint {
        private long t;
        private double l;
        private double s;
    }
}
