package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceCacheManager;

@Component
public class BinanceTestnetApiClient extends BinanceApiClient {

	public BinanceTestnetApiClient() {
		super();
	}

	@Override
	public String getExchangeCode() {
		return "BINANCE_TESTNET";
	}
}
