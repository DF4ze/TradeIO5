package fr.ses10doigts.tradeIO5.model.dto.portfolio;

/**
 * Cash <b>live</b> (stablecoins des wallets lus) et autonomie = cash ÷ dépense quotidienne moyenne des liens (montant de base x
 * multiplicateur moyen). {@code days} et {@code dailySpend} sont nuls sans lien actif.
 */
public record AutonomyDto(double cash, Double days, Double dailySpend) {
}
