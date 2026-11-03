package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.time.Instant;
import java.time.LocalDate;

/** Décision quotidienne d'un lien (passe 23:55 UTC), indépendante de l'exécution : action dimensionnée ou raison de non-action,
 * avec l'état de la machine à la fin du jour ({@code strategyState}, nul si la passe n'a pas été jouée). */
public record DecisionDto(Long bindingId, String assetSymbol, Long walletId, LocalDate day, String status, String action,
                          Double amountUsdc, Double quantity, String blockReason, String modulation, Instant computedAt,
                          StrategyStateDto strategyState) {
}
