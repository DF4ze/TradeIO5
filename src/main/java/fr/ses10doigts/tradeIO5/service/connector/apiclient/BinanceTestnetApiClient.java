package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.springframework.stereotype.Component;

@Component
public class BinanceTestnetApiClient extends BinanceApiClient {

	public BinanceTestnetApiClient() {
		super();
	}

	@Override
	public WebProviderCode getProviderCode() {
		return WebProviderCode.BINANCE_TESTNET;
	}
}
