package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.stereotype.Component;

@Component
public class BinanceTestnetApiClient extends BinanceApiClient {

	public BinanceTestnetApiClient(DomainClock clock) {
		super(clock);
	}

	@Override
	public WebProviderCode getProviderCode() {
		return WebProviderCode.BINANCE_TESTNET;
	}
}
