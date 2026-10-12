package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.util.List;

/**
 * Demande de validation d'une route multi-jambes (testeur d'ordre, aucun envoi) : un ordre par jambe, dans l'ordre d'exécution
 * (ex. {@code BTC-USDC} puis {@code PAXG-BTC}). Aucun secret.
 */
public record RouteValidationRequest(List<OrderTestRequest> legs) {
}
