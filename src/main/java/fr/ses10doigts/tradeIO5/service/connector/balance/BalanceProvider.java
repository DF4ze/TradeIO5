package fr.ses10doigts.tradeIO5.service.connector.balance;

import java.math.BigDecimal;
import java.util.Map;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;

public interface BalanceProvider {
	Map<String, BigDecimal> fetchAllBalances(ApiCredential credential);
}
