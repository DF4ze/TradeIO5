package fr.ses10doigts.tradeIO5.model.dto.dca.bench;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService;

import java.math.BigDecimal;
import java.util.List;

/** Réponse de {@code GET /api/rainbow-live/bindings/{id}/path-quote} : devis du chemin, niveau Fee Test, avertissement. Pourcentages en %, jamais en bps. */
public record PathQuoteDto(PathStatus status, String source, String target, BigDecimal amount, List<PathLeg> legs,
                           BigDecimal totalCostPct, FeeTestLevel feeTestLevel, PathEstimation estimation,
                           boolean underfunded, String warning) {

    public static PathQuoteDto of(BindingPathQuoteService.Result result) {
        PathQuote q = result.quote();
        return new PathQuoteDto(q.status(), q.source(), q.target(), q.amount(), q.legs(), q.totalCostPct(),
                q.feeTestLevel(), q.estimation(), q.underfunded(), result.warning());
    }
}
