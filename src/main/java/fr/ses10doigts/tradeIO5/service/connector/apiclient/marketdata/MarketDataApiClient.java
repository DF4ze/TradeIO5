package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;

import java.time.Instant;
import java.util.List;

/**
 * Client d'accès aux données de marché PUBLIQUES (candles/klines) d'un exchange.
 * <p>
 * Volontairement découplé de {@link fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential} :
 * contrairement au solde ou à l'historique de trades exposés par
 * {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.ProviderApiClient}, les candles
 * sont des données publiques, identiques pour tout le monde, et ne nécessitent aucune clé API
 * ni aucun {@code User} propriétaire.
 */
public interface MarketDataApiClient {

    MarketDataSource getSource();

    List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit);
}
