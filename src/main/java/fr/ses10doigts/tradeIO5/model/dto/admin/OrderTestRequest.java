package fr.ses10doigts.tradeIO5.model.dto.admin;

import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

import java.math.BigDecimal;

/**
 * Demande de validation d'un ordre limit IOC (testeur d'ordre, aucun envoi). Une seule de {@code sz} (base) ou
 * {@code quoteAmount} (cotation) ; {@code px} = plafond (BUY) ou plancher (SELL), requis. Aucun secret.
 */
public record OrderTestRequest(Long userId, WebProviderCode provider, String instId, StepSide side, BigDecimal sz,
                               BigDecimal quoteAmount, BigDecimal px) {
}
