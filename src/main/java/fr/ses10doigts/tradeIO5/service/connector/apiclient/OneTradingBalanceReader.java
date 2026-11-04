package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceCacheManager;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Soldes One Trading en lecture seule : {@code GET /account/balances}, solde {@code available} par {@code currency_code}
 * (soldes nuls absents), mis en cache. Clé API de portée Read suffisante.
 */
@Component
public class OneTradingBalanceReader implements ReadOnlyBalanceReader {

    private static final Logger logger = LoggerFactory.getLogger(OneTradingBalanceReader.class);

    static final String BALANCES_PATH = "/account/balances";

    private final BalanceCacheManager balanceCacheManager;

    public OneTradingBalanceReader(DomainClock clock) {
        this.balanceCacheManager = new BalanceCacheManager(clock);
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.ONE_TRADING;
    }

    @Override
    public Map<String, BigDecimal> getAvailableBalances(ApiCredential credential) {
        return balanceCacheManager.getBalances(this::fetchAvailableBalances, credential);
    }

    @Override
    public Map<String, BigDecimal> fetchAvailableBalances(ApiCredential credential) {
        OneTradingRequests.requireComplete(credential);
        Map<String, BigDecimal> balances = parse(OneTradingRequests.get(credential, BALANCES_PATH));
        logger.info("📦 [ONE_TRADING] {} soldes disponibles lus", balances.size());
        return balances;
    }

    /** Package-private pour les tests. */
    static Map<String, BigDecimal> parse(OneTradingRequests.Reply reply) {
        JsonNode list = OneTradingRequests.requireJson(reply, "account/balances").path("balances");
        if (!list.isArray()) {
            throw new BalanceUnavailableException("One Trading : champ balances absent");
        }
        Map<String, BigDecimal> balances = new HashMap<>();
        for (JsonNode entry : list) {
            String currency = entry.path("currency_code").asText("");
            if (currency.isBlank()) {
                throw new BalanceUnavailableException("One Trading : devise absente dans balances");
            }
            String text = entry.path("available").asText("");
            try {
                BigDecimal available = text.isBlank() ? BigDecimal.ZERO : new BigDecimal(text);
                if (available.signum() > 0) {
                    balances.put(currency.toUpperCase(), available);
                }
            } catch (NumberFormatException e) {
                throw new BalanceUnavailableException("One Trading : solde illisible pour " + currency, e);
            }
        }
        return balances;
    }
}
