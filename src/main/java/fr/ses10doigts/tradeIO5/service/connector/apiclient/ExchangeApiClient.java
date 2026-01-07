package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;

public interface ExchangeApiClient {
    String getExchangeCode();

	BigDecimal getBalance(String assetSymbol, ApiCredential credential);

	Map<String, BigDecimal> getAllBalances(ApiCredential credential);

	BigDecimal getMarketPrice(String assetSymbol, String quoteCurrency, ApiCredential credential);

	List<TradeDto> getHistoricalTrades(Set<String> pairs, ApiCredential credential);

	List<TradeDto> getTradesSince(LocalDateTime date, Set<String> pairs, ApiCredential credential);

}