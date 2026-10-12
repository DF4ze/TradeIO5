package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/**
 * Série D1 native d'un actif et provenance réelle des bougies.
 *
 * @param source         exchange réellement interrogé
 * @param providerSymbol paire chez cet exchange (ex : {@code PAXGUSD})
 * @param substituted    {@code true} si une source était demandée mais que la série vient d'une AUTRE (fallback)
 */
public record DailyDataset(MarketDataset dataset, MarketDataSource source, String providerSymbol, boolean substituted) {
}
