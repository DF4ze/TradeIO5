package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.service.execution.plan.ClOrdIds;

import java.math.BigDecimal;

/**
 * Ordre spot à envoyer : limit IOC plafonné uniquement (prix obligatoire, {@code sz} en unités de la base) ; il n'existe
 * aucun moyen de construire un ordre au marché. Aucun secret.
 */
public record OrderRequest(String instId, StepSide side, StepOrderType ordType, BigDecimal sz, BigDecimal px, String clOrdId) {

    public OrderRequest {
        if (instId == null || instId.isBlank() || side == null) {
            throw new IllegalArgumentException("instId et side sont requis");
        }
        if (ordType != StepOrderType.LIMIT_IOC) {
            throw new IllegalArgumentException("Seul LIMIT_IOC est permis : " + ordType);
        }
        if (sz == null || sz.signum() <= 0 || px == null || px.signum() <= 0) {
            throw new IllegalArgumentException("sz et px (plafond) doivent être strictement positifs");
        }
        if (clOrdId == null || clOrdId.isBlank() || clOrdId.length() > ClOrdIds.MAX_LENGTH
                || !clOrdId.chars().allMatch(c -> c < 128 && Character.isLetterOrDigit(c))) {
            throw new IllegalArgumentException("clOrdId invalide");
        }
    }
}
