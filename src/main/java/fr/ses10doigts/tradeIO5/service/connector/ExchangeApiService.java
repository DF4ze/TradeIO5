package fr.ses10doigts.tradeIO5.service.connector;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.ExchangeApiClient;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExchangeApiService {
    private final Logger logger = LoggerFactory.getLogger(ExchangeApiService.class);

    private final List<ExchangeApiClient> clients;

    private ExchangeApiClient getClient(Wallet wallet) {
        //logger.debug(" {} clients : {}", clients.size(), clients);
        return clients.stream()
            .filter(c -> c.getExchangeCode().equalsIgnoreCase(wallet.getProviderCode()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Exchange inconnu : " + wallet.getProviderCode()));
    }

    public BigDecimal getUserBalance(Wallet wallet, String assetSymbol) {

        ExchangeApiClient client = getClient(wallet);

		return client.getBalance(assetSymbol, wallet.getCredential());
    }

    public boolean buy(Wallet wallet, BigDecimal amount, String asset) {
        return true;
    }

    public boolean sell(Wallet wallet, BigDecimal amount, String asset) {
        return true;
    }

    public Map<String, BigDecimal> getAllBalances(Wallet wallet) {
        ExchangeApiClient client = getClient(wallet);
        return client.getAllBalances(wallet.getCredential());
    }

    public BigDecimal getMarketPrice(Wallet wallet, String asset, String quoteCurrency) {
        ExchangeApiClient client = getClient(wallet);
        return client.getMarketPrice(asset, quoteCurrency, wallet.getCredential());
    }

    public List<TradeDto> getTradesSince(Wallet wallet, LocalDateTime localDateTime, Set<String> pairs) {
        ExchangeApiClient client = getClient(wallet);
        return client.getTradesSince(localDateTime, pairs, wallet.getCredential());

    }

    public List<TradeDto> getHistoricalTrades(Wallet wallet, Set<String> pairs, ApiCredential credential) {
        ExchangeApiClient client = getClient(wallet);
        return client.getHistoricalTrades(pairs, credential);
    }


    // autres méthodes : getAllBalances, getMarketPrice, passer des ordres, etc.
}