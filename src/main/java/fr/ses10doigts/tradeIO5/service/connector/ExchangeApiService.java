package fr.ses10doigts.tradeIO5.service.connector;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.ExchangeApiClient;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExchangeApiService {

    private final List<ExchangeApiClient> clients;
    private final ApiCredentialRepository credentialRepository;

    public ExchangeApiClient getClient(String exchangeCode) {
        return clients.stream()
            .filter(c -> c.getExchangeCode().equalsIgnoreCase(exchangeCode))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Exchange inconnu : " + exchangeCode));
    }

    public BigDecimal getUserBalance(User user, String exchangeCode, String assetSymbol) {

        ExchangeApiClient client = getClient(exchangeCode);
		ApiCredential cred = credentialRepository.findByUserAndExchange_CodeAndEnabledTrue(user, exchangeCode)
            .orElseThrow(() -> new IllegalStateException("Clé API non configurée pour " + exchangeCode));

		return client.getBalance(assetSymbol, cred);
    }

    // autres méthodes : getAllBalances, getMarketPrice, passer des ordres, etc.
}