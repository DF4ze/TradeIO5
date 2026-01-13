package fr.ses10doigts.tradeIO5.service.connector;

import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.ProviderApiClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProviderApiService {
    private final Logger logger = LoggerFactory.getLogger(ProviderApiService.class);

    private final List<ProviderApiClient> clients;

    private ProviderApiClient getClient(Wallet wallet) {
        //logger.debug(" {} clients : {}", clients.size(), clients);
        return clients.stream()
            .filter(c -> c.getProviderCode() == wallet.getWebProviderCode())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Exchange inconnu : " + wallet.getWebProviderCode())); // FIXME gestion Exception
    }

    public BigDecimal getUserBalance(Wallet wallet, String assetSymbol) {

        ProviderApiClient client = getClient(wallet);

		return client.getBalance(assetSymbol, wallet.getCredential());
    }

    public boolean buy(Wallet wallet, BigDecimal amount, String asset) {
        return true;
    }

    public boolean sell(Wallet wallet, BigDecimal amount, String asset) {
        return true;
    }

    public Map<String, BigDecimal> getAllBalances(Wallet wallet) {
        ProviderApiClient client = getClient(wallet);
        return client.getAllBalances(wallet.getCredential());
    }

    public BigDecimal getMarketPrice(Wallet wallet, String asset, String quoteCurrency) {
        ProviderApiClient client = getClient(wallet);
        return client.getMarketPrice(asset, quoteCurrency, wallet.getCredential());
    }

    public List<TradeDto> getTradesSince(Wallet wallet, LocalDateTime localDateTime, Set<String> pairs) {
        ProviderApiClient client = getClient(wallet);
        return client.getTradesSince(localDateTime, pairs, wallet.getCredential());

    }

    public List<TradeDto> getHistoricalTrades(Wallet wallet, Set<String> pairs, ApiCredential credential) {
        ProviderApiClient client = getClient(wallet);
        return client.getHistoricalTrades(pairs, credential);
    }


    // autres méthodes : getAllBalances, getMarketPrice, passer des ordres, etc.
}