package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceCacheManager;

@Service
public class BinanceTestnetApiClient extends BinanceApiClient {

	public BinanceTestnetApiClient(BalanceCacheManager balanceCacheManager) {
		super(balanceCacheManager);
	}

	@Override
	public String getExchangeCode() {
		return "BINANCE_TESTNET";
	}
}
