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
 * Soldes Gate EU en lecture seule : {@code GET /spot/accounts}, solde {@code available} par {@code currency} (soldes nuls
 * absents), mis en cache. Clé API de portée lecture suffisante.
 */
@Component
public class GateEuBalanceReader implements ReadOnlyBalanceReader {

    private static final Logger logger = LoggerFactory.getLogger(GateEuBalanceReader.class);

    static final String ACCOUNTS_PATH = "/spot/accounts";

    private final BalanceCacheManager balanceCacheManager;

    public GateEuBalanceReader(DomainClock clock) {
        this.balanceCacheManager = new BalanceCacheManager(clock);
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.GATE_EU;
    }

    @Override
    public Map<String, BigDecimal> getAvailableBalances(ApiCredential credential) {
        return balanceCacheManager.getBalances(this::fetchAvailableBalances, credential);
    }

    @Override
    public Map<String, BigDecimal> fetchAvailableBalances(ApiCredential credential) {
        GateEuRequests.requireComplete(credential);
        Map<String, BigDecimal> balances = parse(GateEuRequests.get(credential, ACCOUNTS_PATH));
        logger.info("📦 [GATE_EU] {} soldes disponibles lus", balances.size());
        return balances;
    }

    /** Package-private pour les tests. */
    static Map<String, BigDecimal> parse(GateEuRequests.Reply reply) {
        JsonNode list = GateEuRequests.requireJson(reply, "spot/accounts");
        if (!list.isArray()) {
            throw new BalanceUnavailableException("Gate EU : liste de comptes inattendue");
        }
        Map<String, BigDecimal> balances = new HashMap<>();
        for (JsonNode entry : list) {
            String currency = entry.path("currency").asText("");
            if (currency.isBlank()) {
                throw new BalanceUnavailableException("Gate EU : devise absente dans spot/accounts");
            }
            String text = entry.path("available").asText("");
            try {
                BigDecimal available = text.isBlank() ? BigDecimal.ZERO : new BigDecimal(text);
                if (available.signum() > 0) {
                    balances.put(currency.toUpperCase(), available);
                }
            } catch (NumberFormatException e) {
                throw new BalanceUnavailableException("Gate EU : solde illisible pour " + currency, e);
            }
        }
        return balances;
    }
}
