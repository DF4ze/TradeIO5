package fr.ses10doigts.tradeIO5.model.dto.admin;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

/** Relecture (lecture seule, aucun ordre) d'un ordre envoyé par le testeur, identifié par son {@code clOrdId}. */
public record ReconcileRequest(Long userId, WebProviderCode provider, String instId, String clOrdId) {
}
