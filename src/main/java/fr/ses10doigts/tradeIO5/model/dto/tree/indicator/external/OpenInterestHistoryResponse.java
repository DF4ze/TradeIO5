package fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Réponse Coinalyze {@code GET /v1/open-interest-history} (étude "indicateurs-macro-externes" §14
 * item H, prompt d'implémentation Lot 2) : {@code [{ "symbol", "history": [{ "t", "o", "h", "l",
 * "c" }] }]} — même convention "candle" que les autres endpoints {@code -history} de Coinalyze
 * (open/high/low/close de l'Open Interest sur chaque point), par opposition à
 * {@code liquidation-history} qui expose {@code l}/{@code s} (voir {@link LiquidationHistoryResponse}).
 * <p>
 * <b>Forme non vérifiée contre un appel réel</b> (pas de clé Coinalyze disponible au moment de
 * cette implémentation) : le prompt Lot 2 renvoie à l'item C du Lot 1, dont le tableau
 * d'endpoints ne documente que {@code /open-interest} (valeur ponctuelle) — la forme "candle"
 * ci-dessus est déduite par analogie avec la convention Coinalyze pour ses endpoints
 * {@code -history} (funding-rate-history suit la même forme). <b>À confirmer au premier appel
 * réel</b> avant de committer un consommateur qui en dépendrait de façon critique — {@code c}
 * (close) est utilisé comme valeur représentative de chaque point par {@link
 * fr.ses10doigts.tradeIO5.service.tree.indicator.external.OpenInterestIndicator}.
 */
@Data
@NoArgsConstructor
public class OpenInterestHistoryResponse {

    private List<Entry> entries;
    private boolean valid = true;

    public static OpenInterestHistoryResponse invalid() {
        OpenInterestHistoryResponse response = new OpenInterestHistoryResponse();
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
        private double o;
        private double h;
        private double l;
        private double c;
    }
}
