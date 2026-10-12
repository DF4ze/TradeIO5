package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frais réels Kraken du compte : {@code POST /0/private/TradeVolume} avec {@code pair=<nativePair>} et {@code fee-info=true}
 * (clé Read, signature partagée via {@link KrakenSignedRequests}). {@code fees} = taker, {@code fees_maker} = maker, déjà en
 * <b>% du montant</b> ({@code "0.2600"} = 0,26 %), positifs = coût. La paire est lue au catalogue ({@code nativePair}), repli
 * {@link KrakenAssetNames#fallbackPair}. Un champ absent ou illisible lève {@link BalanceUnavailableException} : jamais de
 * frais supposé. Cache mémoire {@link ExecutionDefaults#FEE_CACHE_TTL} par (clé, instrument), échecs non cachés ; throttle
 * simple entre deux appels réseau (compteur d'appels privés Kraken).
 */
@Slf4j
@Component
public class KrakenTradingFeeProvider implements TradingFeeProvider {

    static final String TRADE_VOLUME_METHOD = "TradeVolume";

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record CacheKey(Object credential, String instId) {
    }

    private record Cached(FeeRate rate, Instant fetchedAt) {
    }

    private final DomainClock clock;
    private final InstrumentCatalog catalog;
    private final long minIntervalNanos;
    private final Map<CacheKey, Cached> cache = new ConcurrentHashMap<>();
    private long lastCallNanos;

    @Autowired
    public KrakenTradingFeeProvider(DomainClock clock, InstrumentCatalog catalog) {
        this(clock, catalog, MIN_INTERVAL);
    }

    KrakenTradingFeeProvider(DomainClock clock, InstrumentCatalog catalog, Duration minInterval) {
        this.clock = clock;
        this.catalog = catalog;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.KRAKEN;
    }

    @Override
    public FeeRate getFeeRate(ApiCredential credential, String instId) {
        KrakenSignedRequests.requireComplete(credential);
        CacheKey key = new CacheKey(credential.getId() != null ? credential.getId() : credential.getApiKey(), instId);
        Instant now = clock.now();
        Cached cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plus(ExecutionDefaults.FEE_CACHE_TTL).isAfter(now)) {
            return cached.rate();
        }
        String pair = catalog.nativePair(WebProviderCode.KRAKEN, instId).orElseGet(() -> KrakenAssetNames.fallbackPair(instId));
        throttle();
        String body = KrakenSignedRequests.privatePost(clock, credential, TRADE_VOLUME_METHOD,
                Map.of("pair", pair, "fee-info", "true"));
        FeeRate rate = parse(body);
        cache.put(key, new Cached(rate, now));
        log.debug("📦 [KRAKEN] frais du compte lus pour {} : maker={}% taker={}%", instId, rate.makerPct(), rate.takerPct());
        return rate;
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BalanceUnavailableException("Kraken : attente interrompue", e);
            }
        }
        lastCallNanos = System.nanoTime();
    }

    /** Package-private pour les tests : parsing de la réponse {@code TradeVolume} d'une seule paire. */
    static FeeRate parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new BalanceUnavailableException("Kraken : réponse TradeVolume illisible", e);
        }
        if (root == null || !root.isObject()) {
            throw new BalanceUnavailableException("Kraken : réponse TradeVolume inattendue");
        }
        JsonNode errors = root.path("error");
        if (errors.isArray() && !errors.isEmpty()) {
            String message = "Kraken : erreur API TradeVolume " + errors;
            throw errors.toString().matches(".*(Invalid key|Invalid signature|Permission denied).*")
                    ? new CredentialRejectedException(message) : new BalanceUnavailableException(message);
        }
        JsonNode result = root.path("result");
        BigDecimal taker = firstFee(result.path("fees"), "fees");
        BigDecimal maker = result.path("fees_maker").isObject() && !result.path("fees_maker").isEmpty()
                ? firstFee(result.path("fees_maker"), "fees_maker") : taker;
        return new FeeRate(maker, taker);
    }

    private static BigDecimal firstFee(JsonNode fees, String field) {
        Iterator<JsonNode> values = fees.isObject() ? fees.elements() : null;
        if (values == null || !values.hasNext()) {
            throw new BalanceUnavailableException("Kraken : champ " + field + " absent de la réponse TradeVolume");
        }
        String text = values.next().path("fee").asText("");
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Kraken : frais illisible dans " + field + " de la réponse TradeVolume", e);
        }
    }
}
