package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client dédié au carnet d'ordres Binance ({@code GET /api/v3/depth}, public, sans clé API) —
 * étude "indicateurs-macro-externes" §6a. Volontairement séparé de {@link BinanceMarketDataApiClient}
 * plutôt que d'y ajouter une méthode : ce dernier sert exclusivement les klines pour {@code Bucket}
 * via le contrat {@link MarketDataApiClient} (candles historiques, pré-chargées), alors qu'un
 * carnet d'ordres est une lecture ponctuelle "maintenant" consommée par un {@code Indicator}
 * (voir {@code OrderBookIndicator}) — coupler les deux aurait forcé {@code MarketDataApiClient} à
 * exposer une méthode hors de son contrat candles, ou {@code BinanceMarketDataApiClient} à porter
 * une responsabilité qu'il n'a pas aujourd'hui.
 * <p>
 * Instancie son propre {@link SpotClientImpl} plutôt que de réutiliser celui de
 * {@code BinanceMarketDataApiClient} (qui ne l'expose pas publiquement) : le constructeur
 * sans-clé de {@code SpotClientImpl} n'ouvre aucune connexion réseau (même mécanique que
 * {@code BinanceMarketDataApiClient}), le coût d'une seconde instance est donc négligeable.
 */
@Component
public class BinanceOrderBookApiClient {

    private static final Logger logger = LoggerFactory.getLogger(BinanceOrderBookApiClient.class);

    private final SpotClientImpl client;

    public BinanceOrderBookApiClient() {
        this.client = new SpotClientImpl();
    }

    /**
     * @param limit palier accepté par Binance (5/10/20/50/100/500/1000/5000) ; aucune validation
     *              ici, laissée à l'appelant / à l'API elle-même (qui répond en erreur si le
     *              palier n'est pas respecté, capturé ci-dessous et retourne {@code null}).
     * @return {@code null} en cas de panne/erreur réseau — jamais d'exception qui remonte à
     * l'appelant, même contrat que {@link BinanceMarketDataApiClient#getCandles}.
     */
    public OrderBookSnapshot getOrderBook(String symbol, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        if (limit > 0) {
            params.put("limit", limit);
        }

        try {
            String response = client.createMarket().depth(params);
            return mapDepthResponse(response);
        } catch (BinanceClientException | BinanceConnectorException e) {
            logger.warn("Failed to fetch Binance order book for {} : {}", symbol, e.getMessage());
        } catch (Exception e) {
            logger.warn("Unexpected error while fetching Binance order book for {} : {}", symbol, e.getMessage());
        }
        return null;
    }

    /**
     * Mappe {@code { "lastUpdateId", "bids": [["price","qty"],...], "asks": [...] } } vers
     * {@link OrderBookSnapshot}. Isolée de l'appel réseau pour être testable en unitaire (patron
     * {@code BinanceMarketDataApiClient.mapKlinesResponse}).
     */
    static OrderBookSnapshot mapDepthResponse(String response) {
        JSONObject json = new JSONObject(response);
        List<OrderBookSnapshot.OrderBookLevel> bids = mapLevels(json.getJSONArray("bids"));
        List<OrderBookSnapshot.OrderBookLevel> asks = mapLevels(json.getJSONArray("asks"));
        return new OrderBookSnapshot(bids, asks);
    }

    private static List<OrderBookSnapshot.OrderBookLevel> mapLevels(JSONArray levels) {
        List<OrderBookSnapshot.OrderBookLevel> result = new ArrayList<>(levels.length());
        for (int i = 0; i < levels.length(); i++) {
            JSONArray level = levels.getJSONArray(i);
            BigDecimal price = new BigDecimal(level.getString(0));
            BigDecimal quantity = new BigDecimal(level.getString(1));
            result.add(new OrderBookSnapshot.OrderBookLevel(price, quantity));
        }
        return result;
    }
}
