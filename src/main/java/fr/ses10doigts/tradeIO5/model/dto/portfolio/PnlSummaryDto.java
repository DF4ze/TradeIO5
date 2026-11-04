package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.time.LocalDate;

/**
 * Profits réalisés et latents (hors dépôts/retraits de stables : calcul par actif depuis les trades). {@code realized} = cumul depuis le
 * premier trade ; {@code realizedInPeriod} = gains des ventes de la période ; {@code unrealized} = latent actuel (nul si aucun actif
 * valorisable) ; {@code total} = réalisé cumulé + latent ; {@code totalPct} = total / capital engagé (coût des quantités vendues + coût de la
 * position). {@code fees} = frais cumulés ; {@code volume} = achats + ventes en dollars ; {@code bought} = achats cumulés (capital investi) ; {@code incomplete} = ventes sans achat enregistré ; {@code partial} = actif détenu sans cours ou sans
 * prix moyen (exclu du latent).
 */
public record PnlSummaryDto(String period, LocalDate from, double realized, Double realizedPct, double realizedInPeriod,
                            Double realizedInPeriodPct, int sellsInPeriod, Double unrealized, Double unrealizedPct, Double total,
                            Double totalPct, double fees, double volume, double bought, boolean incomplete, boolean partial) {
}
