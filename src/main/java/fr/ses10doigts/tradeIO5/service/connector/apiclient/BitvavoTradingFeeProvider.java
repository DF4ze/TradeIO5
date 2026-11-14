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

/**
 * Frais réels Bitvavo du compte : {@code GET /account/fees} ({@code maker}, {@code taker}, au niveau racine ou sous
 * {@code fees}). Les frais sont par compte (catégorie A par défaut), pas par marché : le même taux est servi pour toute paire.
 * Lus comme des <b>fractions décimales</b> ({@code "0.0025"} = 0,25 %, exemple de la doc, unité non dite explicitement) et
 * convertis en %. Champ absent ou illisible => {@link BalanceUnavailableException}, jamais de frais supposé. Pas de cache ici : {@code TradingFeeService} (24 h).
 */
@Slf4j
@Component
public class BitvavoTradingFeeProvider implements TradingFeeProvider {

    static final String FEES_PATH = "/account/fees";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Duration MIN_INTERVAL = Duration.ofMillis(500);
    private final DomainClock clock;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public BitvavoTradingFeeProvider(DomainClock clock) {
        this(clock, MIN_INTERVAL);
    }

    BitvavoTradingFeeProvider(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public boolean accountWide() {
        return true;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.BITVAVO;
    }

    @Override
    public FeeRate getFeeRate(ApiCredential credential, String instId) {
        BitvavoRequests.requireComplete(credential);
        throttle();
        FeeRate rate = parse(BitvavoRequests.get(credential, FEES_PATH));
        log.debug("📦 [BITVAVO] frais du compte lus : maker={}% taker={}%", rate.makerPct(), rate.takerPct());
        return rate;
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BalanceUnavailableException("Bitvavo : attente interrompue", e);
            }
        }
        lastCallNanos = System.nanoTime();
    }

    /** Package-private pour les tests. */
    static FeeRate parse(BitvavoRequests.Reply reply) {
        JsonNode root = BitvavoRequests.requireJson(reply, "account/fees");
        JsonNode fees = root.path("fees").isObject() ? root.path("fees") : root;
        return new FeeRate(percent(fees, "maker"), percent(fees, "taker"));
    }

    private static BigDecimal percent(JsonNode fees, String field) {
        try {
            return new BigDecimal(fees.path(field).asText("")).multiply(HUNDRED).stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Bitvavo : " + field + " illisible dans la réponse account/fees", e);
        }
    }
}
