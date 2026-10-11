package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.math.BigDecimal;
import java.util.List;

/**
 * Résultat du testeur d'ordre (aucun ordre n'est jamais créé). {@code blockedBy} : verrou global non levé (aucun appel
 * exchange) ; {@code localChecks} : contrôles locaux toujours calculés ; {@code rawFiltered} : réponse de l'exchange réduite à
 * une liste blanche de champs, tronquée à 4 Ko. Aucun secret, aucune signature, aucun nonce.
 */
public record ValidationReportDto(boolean accepted, String blockedBy, List<LocalCheck> localChecks,
                                  NormalizedRequest normalizedRequest, String exchangeCode, String message, String rawFiltered,
                                  Limits limits) {

    public record LocalCheck(String name, boolean ok, String detail) {
    }

    public record NormalizedRequest(String instId, String side, String ordType, BigDecimal sz, BigDecimal px, BigDecimal cost) {
    }

    public record Limits(BigDecimal minSz, BigDecimal costMin, BigDecimal lotSz, BigDecimal tickSz, String feeCurrency) {
    }
}
