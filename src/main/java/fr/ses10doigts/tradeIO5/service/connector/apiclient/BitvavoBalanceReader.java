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
 * Soldes Bitvavo en lecture seule : {@code GET /balance}, {@code available} par {@code symbol} (soldes nuls absents), mis en
 * cache. Clé API avec le droit « Voir » suffisante.
 */
@Component
public class BitvavoBalanceReader implements ReadOnlyBalanceReader {

    private static final Logger logger = LoggerFactory.getLogger(BitvavoBalanceReader.class);

    static final String BALANCE_PATH = "/balance";

    private final BalanceCacheManager balanceCacheManager;

    public BitvavoBalanceReader(DomainClock clock) {
        this.balanceCacheManager = new BalanceCacheManager(clock);
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.BITVAVO;
    }

    @Override
    public Map<String, BigDecimal> getAvailableBalances(ApiCredential credential) {
        return balanceCacheManager.getBalances(this::fetchAvailableBalances, credential);
    }

    @Override
    public Map<String, BigDecimal> fetchAvailableBalances(ApiCredential credential) {
        BitvavoRequests.requireComplete(credential);
        Map<String, BigDecimal> balances = parse(BitvavoRequests.get(credential, BALANCE_PATH));
        logger.info("📦 [BITVAVO] {} soldes disponibles lus", balances.size());
        return balances;
    }

    /** Package-private pour les tests. */
    static Map<String, BigDecimal> parse(BitvavoRequests.Reply reply) {
        JsonNode list = BitvavoRequests.requireJson(reply, "balance");
        if (!list.isArray()) {
            throw new BalanceUnavailableException("Bitvavo : liste de soldes inattendue");
        }
        Map<String, BigDecimal> balances = new HashMap<>();
        for (JsonNode entry : list) {
            String symbol = entry.path("symbol").asText("");
            if (symbol.isBlank()) {
                throw new BalanceUnavailableException("Bitvavo : symbole absent dans balance");
            }
            String text = entry.path("available").asText("");
            try {
                BigDecimal available = text.isBlank() ? BigDecimal.ZERO : new BigDecimal(text);
                if (available.signum() > 0) {
                    balances.put(symbol.toUpperCase(), available);
                }
            } catch (NumberFormatException e) {
                throw new BalanceUnavailableException("Bitvavo : solde illisible pour " + symbol, e);
            }
        }
        return balances;
    }
}
