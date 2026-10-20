package fr.ses10doigts.tradeIO5.model.dto;

import fr.ses10doigts.tradeIO5.model.enumerate.AlertType;

import java.time.LocalDate;

/** Alerte affichée à l'utilisateur (dans l'app seulement). {@code severity} : WARNING ou ERROR. */
public record AlertDto(AlertType type, String severity, String assetSymbol, Long bindingId, String message, LocalDate day) {
}
