package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.math.BigDecimal;

/** Prix limite proposé pour le testeur d'ordre : {@code px} = meilleur ask × (1 + tol) en BUY, meilleur bid × (1 − tol) en SELL. */
public record PriceSuggestionDto(BigDecimal px, BigDecimal bookPx, BigDecimal tolerancePct, String estimation) {
}
