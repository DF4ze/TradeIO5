package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frais réels Gate EU du compte : {@code GET /wallet/fee?currency_pair=BTC_EUR} ({@code /spot/fee} est déprécié), champs
 * {@code maker_fee} / {@code taker_fee}. La doc ne donne pas l'unité : lus comme des <b>fractions</b> du montant
 * ({@code "0.002"} = 0,2 %), donc convertis en % ({@code × 100}) ; hypothèse à confirmer sur une réponse réelle. Champ absent ou
 * illisible => {@link BalanceUnavailableException}, jamais de frais supposé. Cache mémoire {@link ExecutionDefaults#FEE_CACHE_TTL}
 * par (clé, paire), échecs non cachés.
 */
@Slf4j
@Component
public class GateEuTradingFeeProvider implements TradingFeeProvider {

    static final String FEE_PATH = "/wallet/fee?currency_pair=";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Duration MIN_INTERVAL = Duration.ofMillis(500);

    private record Cached(FeeRate rate, Instant fetchedAt) {
    }

    private final DomainClock clock;
    private final long minIntervalNanos;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private long lastCallNanos;

    @Autowired
    public GateEuTradingFeeProvider(DomainClock clock) {
        this(clock, MIN_INTERVAL);
    }

    GateEuTradingFeeProvider(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.GATE_EU;
    }

    @Override
    public FeeRate getFeeRate(ApiCredential credential, String instId) {
        GateEuRequests.requireComplete(credential);
        String pair = GateEuRequests.nativePair(instId);
        String key = (credential.getId() != null ? credential.getId() : credential.getApiKey()) + "/" + pair;
        Instant now = clock.now();
        Cached cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plus(ExecutionDefaults.FEE_CACHE_TTL).isAfter(now)) {
            return cached.rate();
        }
        throttle();
        FeeRate rate = parse(GateEuRequests.get(credential, FEE_PATH + pair));
        cache.put(key, new Cached(rate, now));
        log.debug("📦 [GATE_EU] frais lus pour {} : maker={}% taker={}%", pair, rate.makerPct(), rate.takerPct());
        return rate;
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BalanceUnavailableException("Gate EU : attente interrompue", e);
            }
        }
        lastCallNanos = System.nanoTime();
    }

    /** Package-private pour les tests. */
    static FeeRate parse(GateEuRequests.Reply reply) {
        JsonNode root = GateEuRequests.requireJson(reply, "wallet/fee");
        return new FeeRate(percent(root, "maker_fee"), percent(root, "taker_fee"));
    }

    private static BigDecimal percent(JsonNode root, String field) {
        try {
            return new BigDecimal(root.path(field).asText("")).multiply(HUNDRED).stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Gate EU : " + field + " illisible dans la réponse wallet/fee", e);
        }
    }
}
