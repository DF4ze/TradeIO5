package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.time.LocalDate;

/** Point quotidien du patrimoine (tous wallets confondus) ; une valeur est nulle (« — ») si un wallet n'était pas exploitable ce jour-là. */
public record PortfolioPointDto(LocalDate day, Double total, Double stable, Double assets, Double exposurePct) {
}
