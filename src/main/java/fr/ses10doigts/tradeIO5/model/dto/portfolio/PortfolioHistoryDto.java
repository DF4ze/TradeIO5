package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.time.LocalDate;
import java.util.List;

/**
 * Historique de patrimoine <b>reconstruit</b> : soldes actuels remontés à rebours en annulant les trades enregistrés, valorisés
 * au cours D1 de chaque jour. Estimation : dépôts/retraits non suivis ({@code flowsIncluded=false}), la courbe dérive après un
 * virement. Valeur nulle un jour sans cours.
 */
public record PortfolioHistoryDto(Long walletId, String period, LocalDate from, LocalDate to, boolean estimated,
                                  boolean flowsIncluded, PortfolioSummaryDto.Pnl pnl, List<PortfolioPointDto> series) {
}
