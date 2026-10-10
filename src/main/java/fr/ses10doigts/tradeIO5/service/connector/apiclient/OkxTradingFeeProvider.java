package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frais réels OKX du compte : {@code GET /api/v5/account/trade-fee?instType=SPOT&instId=...} (clé Read, signature
 * partagée avec {@link OkxBalanceReader} via {@link OkxSignedRequests}).
 * <p>
 * OKX renvoie des taux <b>signés</b> (négatif = frais payé, positif = rebate) : {@code maker}/{@code taker} pour les
 * paires USDT et {@code makerUSDC}/{@code takerUSDC} pour les paires cotées en USDC. Normalisés en {@link FeeRate}
 * (% du montant, positif = coût). Un champ absent ou illisible lève {@link BalanceUnavailableException} : jamais de
 * frais supposé. Cache mémoire 24 h sur {@link DomainClock} (échecs non cachés) ; throttle simple entre deux appels
 * réseau (limite OKX : 5 requêtes / 2 s).
 */
@Slf4j
@Component
public class OkxTradingFeeProvider implements TradingFeeProvider {

    static final String TRADE_FEE_PATH = "/api/v5/account/trade-fee";

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration MIN_INTERVAL = Duration.ofMillis(500);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String USDC = "USDC";

    private record CacheKey(Object credential, String instId) {
    }

    private record Cached(FeeRate rate, Instant fetchedAt) {
    }

    private final DomainClock clock;
    private final long minIntervalNanos;
    private final Map<CacheKey, Cached> cache = new ConcurrentHashMap<>();
    private long lastCallNanos;

    @Autowired
    public OkxTradingFeeProvider(DomainClock clock) {
        this(clock, MIN_INTERVAL);
    }

    OkxTradingFeeProvider(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public FeeRate getFeeRate(ApiCredential credential, String instId) {
        OkxSignedRequests.requireComplete(credential);
        CacheKey key = new CacheKey(credential.getId() != null ? credential.getId() : credential.getApiKey(), instId);
        Instant now = clock.now();
        Cached cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.rate();
        }
        throttle();
        String body = OkxSignedRequests.signedGet(clock, credential, TRADE_FEE_PATH + "?instType=SPOT&instId=" + instId);
        FeeRate rate = parse(body, instId);
        cache.put(key, new Cached(rate, now));
        log.info("📦 [OKX] frais du compte lus pour {}", instId);
        log.debug("OKX frais {} : maker={}% taker={}%", instId, rate.makerPct(), rate.takerPct());
        return rate;
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BalanceUnavailableException("OKX : attente interrompue", e);
            }
        }
        lastCallNanos = System.nanoTime();
    }

    /** Package-private pour les tests : parsing de la réponse {@code /account/trade-fee} pour {@code instId}. */
    static FeeRate parse(String body, String instId) {
        JsonNode root = OkxSignedRequests.readData(body);
        JsonNode data = root.path("data").path(0);
        if (data.isMissingNode() || !data.isObject()) {
            throw new BalanceUnavailableException("OKX : frais absents de la réponse trade-fee");
        }
        boolean usdcPair = instId.endsWith("-" + USDC);
        BigDecimal maker = rate(data, usdcPair ? "makerUSDC" : "maker");
        BigDecimal taker = rate(data, usdcPair ? "takerUSDC" : "taker");
        return new FeeRate(maker.negate().multiply(HUNDRED), taker.negate().multiply(HUNDRED));
    }

    private static BigDecimal rate(JsonNode data, String field) {
        String text = data.path(field).asText("");
        if (text.isBlank()) {
            throw new BalanceUnavailableException("OKX : champ " + field + " absent de la réponse trade-fee");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("OKX : champ " + field + " illisible dans la réponse trade-fee", e);
        }
    }
}
