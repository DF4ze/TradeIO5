package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

import java.util.OptionalInt;

/** Nombre de jours de bougies D1 closes d'un actif chez un provider, par le cache DB des bougies (jamais un appel par vérification). */
public interface D1HistoryProbe {

    /** Jours D1 closes (plafonnés à {@link AssetEligibilityDefaults#MIN_D1_DAYS}) ; 0 si le provider n'a pas la paire / pas de client de bougies ; vide si la lecture est impossible. */
    OptionalInt closedDays(WebProviderCode provider, String assetSymbol);
}
