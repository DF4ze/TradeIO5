package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.time.LocalDate;
import java.util.List;

/**
 * Patrimoine et variation sur une période. {@code pnl} = variation de valeur entre le premier et le dernier point exploitable de
 * la période (nul sous deux points) ; <b>les dépôts/retraits ne sont pas suivis</b> ({@code flowsIncluded=false}, à afficher).
 * {@code autonomyDays} = stable actuel ÷ dépense quotidienne moyenne des liens (nul si indéterminable).
 */
public record PortfolioSummaryDto(String period, LocalDate from, LocalDate to, PortfolioPointDto current, Pnl pnl,
                                  List<PortfolioPointDto> series, Double autonomyDays, boolean flowsIncluded) {

    public record Pnl(double amount, Double pct, LocalDate since) {
    }
}
