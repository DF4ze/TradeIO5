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
 * Soldes OKX en lecture seule : compte <b>Trading</b>, {@code GET /api/v5/account/balance}, solde disponible
 * ({@code availBal}) par devise. Un seul appel par lecture (limite OKX : 10 requêtes / 2 s), mis en cache.
 * Clé API attendue : permission « Read » uniquement, IP allowlistée.
 * <p>
 * Authentification : {@code OK-ACCESS-SIGN = base64(HMAC-SHA256(secret, timestamp + "GET" + chemin + corps))}, timestamp
 * ISO 8601 UTC à la milliseconde (écart toléré par OKX : 30 s). Aucun secret n'est loggué ni mis dans une exception.
 */
@Component
public class OkxBalanceReader implements ReadOnlyBalanceReader {

    private static final Logger logger = LoggerFactory.getLogger(OkxBalanceReader.class);

    static final String BALANCE_PATH = "/api/v5/account/balance";

    private final DomainClock clock;
    private final BalanceCacheManager balanceCacheManager;

    public OkxBalanceReader(DomainClock clock) {
        this.clock = clock;
        this.balanceCacheManager = new BalanceCacheManager(clock);
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public Map<String, BigDecimal> getAvailableBalances(ApiCredential credential) {
        return balanceCacheManager.getBalances(this::fetchAvailableBalances, credential);
    }

    @Override
    public Map<String, BigDecimal> fetchAvailableBalances(ApiCredential credential) {
        String body = OkxSignedRequests.signedGet(clock, credential, BALANCE_PATH);
        Map<String, BigDecimal> balances = parseBalances(body);
        logger.info("📦 [OKX] {} soldes disponibles lus (compte Trading)", balances.size());
        return balances;
    }

    /** Package-private pour les tests : parsing de la réponse {@code /account/balance}. */
    static Map<String, BigDecimal> parseBalances(String body) {
        JsonNode root = OkxSignedRequests.readData(body);
        JsonNode details = root.path("data").path(0).path("details");
        if (!details.isArray()) {
            throw new BalanceUnavailableException("OKX : champ data[0].details absent");
        }

        Map<String, BigDecimal> balances = new HashMap<>();
        for (JsonNode detail : details) {
            String ccy = detail.path("ccy").asText("");
            if (ccy.isBlank()) {
                throw new BalanceUnavailableException("OKX : devise absente dans details");
            }
            BigDecimal available = parseAmount(detail.path("availBal").asText(""), ccy);
            if (available.signum() > 0) {
                balances.put(ccy.toUpperCase(), available);
            }
        }
        return balances;
    }

    private static BigDecimal parseAmount(String text, String ccy) {
        if (text.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("OKX : solde illisible pour " + ccy, e);
        }
    }
}
