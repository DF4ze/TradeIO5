package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.math.BigDecimal;
import java.util.List;

/**
 * Résultat d'un envoi réel ou d'une relecture depuis le testeur. {@code status} : {@code SENT} (accepté par l'exchange),
 * {@code BLOCKED} (verrou global non levé, rien envoyé), {@code REFUSED} (contrôles locaux ou validation en échec, rien
 * envoyé), {@code REJECTED} (rejet métier certain), {@code UNKNOWN} (issue inconnue : à relire par {@code clOrdId}),
 * {@code RECONCILED} / {@code NOT_FOUND} (relecture). Aucun secret.
 */
public record RealOrderResultDto(String status, ValidationReportDto validation, String clOrdId, String ordId, String phase,
                                 BigDecimal filledSz, BigDecimal avgPx, List<FillDto> fills, String message) {

    public record FillDto(String tradeId, BigDecimal px, BigDecimal sz, BigDecimal fee, String feeCcy) {
    }
}
