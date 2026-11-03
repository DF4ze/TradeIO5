package fr.ses10doigts.tradeIO5.model.dto.portfolio;

/**
 * État de la machine Rainbow d'un lien à la fin d'un jour, pour le Journal : pourquoi « rien à faire » (attente d'un
 * trailing-stop, verrou jusqu'à Down2, cooldown, moon...). Lecture seule, figé par la passe du jour. Les champs
 * numériques sont nuls quand ils n'ont pas de sens pour le {@code kind}.
 *
 * @param kind        {@code MOON_ACTIVE}, {@code SELL_TRAILING}, {@code COOLDOWN}, {@code LOCKED_UNTIL_DOWN2},
 *                    {@code BUY_TRAILING} ou {@code WATCHING}
 * @param referencePrice plus bas (attente d'achat) / plus haut (vente, moon) depuis l'armement
 * @param triggerPrice   niveau qui déclenche l'ordre (trailing) ; nul sinon
 * @param down2          borne Down2 du jour (verrou jusqu'à Down2)
 * @param cooldownDays   jours de cooldown restants
 * @param armedDays      jours depuis l'armement (trailing)
 * @param buyLocked      le verrou d'achat « jusqu'à Down2 » est encore actif (peut accompagner un cooldown)
 * @param zone           zone Rainbow du jour (0 = EXTREME_BAS ... 5 = EXTREME_HAUT)
 */
public record StrategyStateDto(String kind, Double close, Double referencePrice, Double triggerPrice, Double down2,
                               Integer cooldownDays, Integer armedDays, boolean buyLocked, Integer zone,
                               String activeSet, String trendRegime) {
}
