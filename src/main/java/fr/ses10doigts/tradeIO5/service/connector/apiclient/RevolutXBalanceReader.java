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
 * Soldes Revolut X en lecture seule : {@code GET /1.0/balances} (tableau de {@code {currency, available, reserved, total}}),
 * solde {@code available} par devise (soldes nuls absents), mis en cache. Clé API de portée Read suffisante.
 */
@Component
public class RevolutXBalanceReader implements ReadOnlyBalanceReader {

    private static final Logger logger = LoggerFactory.getLogger(RevolutXBalanceReader.class);

    static final String BALANCES_PATH = "/1.0/balances";

    private final BalanceCacheManager balanceCacheManager;

    public RevolutXBalanceReader(DomainClock clock) {
        this.balanceCacheManager = new BalanceCacheManager(clock);
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.REVOLUT_X;
    }

    @Override
    public Map<String, BigDecimal> getAvailableBalances(ApiCredential credential) {
        return balanceCacheManager.getBalances(this::fetchAvailableBalances, credential);
    }

    @Override
    public Map<String, BigDecimal> fetchAvailableBalances(ApiCredential credential) {
        RevolutXRequests.requireComplete(credential);
        Map<String, BigDecimal> balances = parse(RevolutXRequests.get(credential, BALANCES_PATH, null));
        logger.info("📦 [REVOLUT_X] {} soldes disponibles lus", balances.size());
        return balances;
    }

    /** Package-private pour les tests. */
    static Map<String, BigDecimal> parse(RevolutXRequests.Reply reply) {
        JsonNode root = RevolutXRequests.requireJson(reply, "balances");
        JsonNode list = root.isArray() ? root : root.path("data");
        if (!list.isArray()) {
            throw new BalanceUnavailableException("Revolut X : liste de soldes absente");
        }
        Map<String, BigDecimal> balances = new HashMap<>();
        for (JsonNode entry : list) {
            String currency = entry.path("currency").asText("");
            if (currency.isBlank()) {
                throw new BalanceUnavailableException("Revolut X : devise absente dans balances");
            }
            String text = entry.path("available").asText("");
            try {
                BigDecimal available = text.isBlank() ? BigDecimal.ZERO : new BigDecimal(text);
                if (available.signum() > 0) {
                    balances.put(currency.toUpperCase(), available);
                }
            } catch (NumberFormatException e) {
                throw new BalanceUnavailableException("Revolut X : solde illisible pour " + currency, e);
            }
        }
        return balances;
    }
}
