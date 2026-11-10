package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frais Revolut X : {@code GET /1.0/configuration/fees/tiers} (signé, clé de lecture) renvoie, par type de produit, la grille de paliers
 * et le palier courant du compte ({@code current_tier}, fonction du volume sur 30 jours). On lit le produit {@code spot}. Les taux de
 * l'API sont des fractions ({@code 0.0009} = 0,09 %), convertis en % du montant. Le tarif est le même pour toutes les paires : le résultat
 * est mis en cache {@link #TTL} par clé API (limite 100 requêtes/minute). Réponse absente, sans {@code spot}, sans palier courant ou
 * illisible => {@link BalanceUnavailableException} (Fee Test indisponible), jamais de frais supposé.
 */
@Component
public class RevolutXTradingFeeProvider implements TradingFeeProvider {

    static final String TIERS_PATH = "/1.0/configuration/fees/tiers";
    static final Duration TTL = Duration.ofMinutes(5);
    private static final String SPOT = "spot";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private record Cached(FeeRate rate, Instant fetchedAt) {
    }

    private final DomainClock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RevolutXTradingFeeProvider(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.REVOLUT_X;
    }

    @Override
    public FeeRate getFeeRate(ApiCredential credential, String instId) {
        RevolutXRequests.requireComplete(credential);
        Instant now = clock.now();
        Cached cached = cache.get(credential.getApiKey());
        if (cached != null && now.isBefore(cached.fetchedAt().plus(TTL))) {
            return cached.rate();
        }
        JsonNode root = RevolutXRequests.requireJson(RevolutXRequests.get(credential, TIERS_PATH, null), "grille de frais");
        FeeRate rate = spotRate(RevolutXRequests.dataOf(root));
        cache.put(credential.getApiKey(), new Cached(rate, now));
        return rate;
    }

    /** Taux du palier courant du produit spot ; visible pour les tests. */
    static FeeRate spotRate(JsonNode data) {
        if (data != null && data.isArray()) {
            for (JsonNode product : data) {
                if (SPOT.equals(product.path("type").asText()) && product.path("current_tier").isNumber()) {
                    int current = product.path("current_tier").asInt();
                    for (JsonNode tier : product.path("tiers")) {
                        if (tier.path("tier").asInt(-1) == current) {
                            return new FeeRate(pct(tier, "maker_fee"), pct(tier, "taker_fee"));
                        }
                    }
                }
            }
        }
        throw new BalanceUnavailableException("Revolut X : palier de frais spot introuvable dans la réponse");
    }

    private static BigDecimal pct(JsonNode tier, String field) {
        try {
            BigDecimal fraction = new BigDecimal(tier.path(field).asText());
            if (fraction.signum() < 0) {
                throw new NumberFormatException("négatif");
            }
            return fraction.multiply(HUNDRED).stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Revolut X : " + field + " illisible dans la grille de frais", e);
        }
    }
}
