package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.enumerate.ProviderCode;
import org.springframework.stereotype.Component;

@Component
public class BinanceTestnetApiClient extends BinanceApiClient {

	public BinanceTestnetApiClient() {
		super();
	}

	@Override
	public ProviderCode getProviderCode() {
		return ProviderCode.BINANCE_TESTNET;
	}
}
