package fr.ses10doigts.tradeIO5.model.dto.admin;

/**
 * Demande d'envoi d'un ordre RÉEL depuis le testeur : la même demande que la validation, plus la confirmation explicite
 * (texte à saisir). Sans confirmation exacte, rien n'est envoyé.
 */
public record RealOrderRequest(OrderTestRequest order, String confirm) {
}
