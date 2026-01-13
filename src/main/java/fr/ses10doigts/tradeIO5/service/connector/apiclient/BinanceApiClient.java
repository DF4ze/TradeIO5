package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceCacheManager;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceProvider;
import fr.ses10doigts.tradeIO5.service.tool.StringTool;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Component
public class BinanceApiClient implements ProviderApiClient, BalanceProvider {

	private static final Logger logger = LoggerFactory.getLogger(BinanceApiClient.class);

	private static final String USDC = "USDC";
	private static final String USDT = "USDT";

	private final BalanceCacheManager balanceCacheManager;

	public BinanceApiClient() {
		this.balanceCacheManager = new BalanceCacheManager();
	}

    @Override
    public WebProviderCode getProviderCode() {
		return WebProviderCode.BINANCE;
    }

    @Override
	public BigDecimal getMarketPrice(String baseAsset, String quoteCurrency, ApiCredential credential ) {
		BigDecimal price = getTickerSymbol(baseAsset, quoteCurrency, credential);

		if (BigDecimal.ZERO.compareTo(price) == 0 && quoteCurrency.equals("EUR")) {
			// 🔹 Tentative directe : BTC/EUR
			price = getTickerSymbol(baseAsset, quoteCurrency, credential);

			if (BigDecimal.ZERO.compareTo(price) == 0) {
				// 🔁 Fallback : BTC/USDC ÷ EURUSDC
				price = getTickerSymbol(baseAsset, USDC, credential);
				if (BigDecimal.ZERO.compareTo(price) == 0)
					price = getTickerSymbol(baseAsset, USDT, credential);

				BigDecimal eurUsdcRate = getEURUSDConversionRate(credential);
				price = price.divide(eurUsdcRate, 8, RoundingMode.HALF_UP);
			}
		}

		if( price == null || BigDecimal.ZERO.compareTo(price) == 0 )
            logger.warn("Unable to retrieve price with pair : {} {}", baseAsset, quoteCurrency);

		return price;
	}

	private BigDecimal getTickerSymbol(String baseAsset, String quoteCurrency, ApiCredential credential) {
		String pair = baseAsset;
		if (!baseAsset.endsWith(quoteCurrency))
			pair += quoteCurrency;

		if (pair.startsWith("LD")) {
			pair = pair.substring(2);
		}

		Map<String, Object> params = Map.of("symbol", pair);
		BigDecimal result = BigDecimal.ZERO;
		try { // FIXME : gestion erreur client
			String response = getClient(credential).createMarket().tickerSymbol(params);
			JSONObject json = new JSONObject(response);
			result = new BigDecimal(json.getString("price"));
		} catch (Exception e) {
            logger.warn("{} cannot be retrieve from tickerSymbol()", pair);
		}

		return result;
	}

	private BigDecimal getEURUSDConversionRate(ApiCredential credential) {
		try {
			Map<String, Object> params = Map.of("symbol", "EURUSDC");
			JSONObject result = new JSONObject(getClient(credential).createMarket().tickerSymbol(params));
			return new BigDecimal(result.getString("price")); // exemple : 1.09 → 1 EUR = 1.09 USDC
		} catch (BinanceClientException e) {
			throw new IllegalStateException("Unable to retrieve EUR/USDC rate from Binance", e);
		}
	}

	@Override
	public BigDecimal getBalance(String assetSymbol, ApiCredential credential) {
		return getAllBalances(credential).getOrDefault(assetSymbol.toUpperCase(), BigDecimal.ZERO);
    }

    @Override
	public Map<String, BigDecimal> getAllBalances(ApiCredential credential) {
		if( credential == null ) {
			logger.warn("Credential is null");
			return new HashMap<>();
		}

		return balanceCacheManager.getBalances(credential.getApiKey() + ":" + credential.getWebProvider().getApiBaseUrl(),
				this, credential);
    }
    
    @Override
    public Map<String, BigDecimal> fetchAllBalances(ApiCredential credential) {

		String response = getClient(credential).createTrade().account(new HashMap<>());
        JSONArray balances = new JSONObject(response).getJSONArray("balances");

        Map<String, BigDecimal> result = new HashMap<>();
        for (int i = 0; i < balances.length(); i++) {
            JSONObject bal = balances.getJSONObject(i);
            BigDecimal free = new BigDecimal(bal.getString("free"));
            if (free.compareTo(BigDecimal.ZERO) > 0) {
                result.put(bal.getString("asset"), free);
            }
        }

        logger.info("\uD83D\uDCE6 [{}] {} balances récupérées pour {}", credential.getWebProvider().getCode(), result.size(), credential.getUser().getUsername());
        return result;
    }

	private SpotClientImpl getClient(ApiCredential credential) {
		return new SpotClientImpl(credential.getApiKey(), credential.getSecretKey(),
				credential.getWebProvider().getApiBaseUrl());
	}

	@Override
	public List<TradeDto> getHistoricalTrades( Set<String> pairs, ApiCredential credential) {
		return getTradesSince( LocalDateTime.of(2020, 1, 1, 0, 0), pairs, credential);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<TradeDto> getTradesSince( LocalDateTime date, Set<String> pairs, ApiCredential credential) {
		SpotClientImpl client = getClient(credential);

		long startTimeMillis = date.toInstant(ZoneOffset.UTC).toEpochMilli();

		List<TradeDto> trades = new ArrayList<>();
		for (String pair : pairs) {

			pair = StringTool.cleansBinanceLD(pair);

			if( pair.startsWith("USD") )
				continue;

			Map<String, Object> parameters = new HashMap<>();
			parameters.put("symbol", pair);
			parameters.put("startTime", startTimeMillis);
			// parameters.put("limit", 1000); // max 1000 par appel

			try {
				// La méthode userTrades renvoie une List<Map<String,Object>> dans "data"

				String myTrades = "[]";
				try {
					myTrades = client.createTrade().myTrades(parameters);

				} catch (BinanceClientException e) {
					logger.warn("Exception on trades for pair {} : {}", pair, e.getMessage());
					continue;
				}

				if ("[]".equals(myTrades)) {
					// logger.debug(pair + " ok but empty");
					continue;
				}
                logger.debug("Text received : {}", myTrades);

				JSONArray jsonArray = new JSONArray(myTrades);
				List<Object> rawList = jsonArray.toList();

				List<Map<String, Object>> rawTrades = new ArrayList<>();
				int count = 0;
				for (Object obj : rawList) {
					if (obj instanceof Map) {
						count++;
						rawTrades.add((Map<String, Object>) obj);
					}
				}
				// logger.debug(count + " trades read");

				if (!rawTrades.isEmpty()) {
					trades.addAll(rawTrades.stream().map(this::mapApiTradeToTradeDto).toList());
					// logger.debug(trades.size() + " trades created for pair " + pair);
				}
			} catch (BinanceConnectorException e) {
				// logger.error("Erreur API Binance", e);
				throw e;
			}
		}
		return trades;
	}

	private TradeDto mapApiTradeToTradeDto(Map<String, Object> rawTrade) {
		// Exemple de mapping, adapte selon la structure renvoyée par Binance
		String id = String.valueOf(rawTrade.get("orderId"));
		String symbol = (String) rawTrade.get("symbol");
		String asset = symbol.substring(0, symbol.length() - 4); // supposer la paire en format ASSET + QUOTE (ex:
																	// BTCUSDT)
		BigDecimal qty = new BigDecimal((String) rawTrade.get("qty"));
		BigDecimal price = new BigDecimal((String) rawTrade.get("price"));
		long time = ((Number) rawTrade.get("time")).longValue();
		LocalDateTime timestamp = LocalDateTime.ofEpochSecond(time / 1000, 0, ZoneOffset.UTC);
		String isBuyer = String.valueOf(rawTrade.get("isBuyer"));
		TradeSide side = "true".equalsIgnoreCase(isBuyer) ? TradeSide.BUY : TradeSide.SELL;
		BigDecimal fee = rawTrade.containsKey("commission") ? new BigDecimal((String) rawTrade.get("commission"))
				: BigDecimal.ZERO;

		//@formatter:off
        return TradeDto.builder()
                .tradeId(id)
                .asset(asset)
                .quantity(qty)
                .price(price)
                .timestamp(timestamp)
                .side(side)
                .fee(fee)
                .build();
        //@formatter:on
	}
}