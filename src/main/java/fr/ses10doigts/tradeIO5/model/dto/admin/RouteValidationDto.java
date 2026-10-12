package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.util.List;

/**
 * Verdict de validation d'une route, jambe par jambe ({@code validate=true}, aucun ordre créé). {@code note} : lecture du verdict
 * quand une jambe après la première est refusée faute de fonds (attendu tant que la jambe précédente n'est pas exécutée).
 */
public record RouteValidationDto(List<ValidationReportDto> legs, boolean allAccepted, String note) {
}
