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
 * Frais réels One Trading du compte : {@code GET /account/fees}, palier actif du groupe {@code SPOT}
 * ({@code active_fee_tiers[].maker_fee / taker_fee}). Les frais sont par <b>groupe de produits</b>, pas par instrument : le même
 * taux est servi pour toute paire. Lus comme des <b>% du montant</b> ({@code "0.1000"} = 0,1 %), hypothèse à confirmer sur une
 * réponse réelle (la doc n'indique pas l'unité). Champ absent ou illisible => {@link BalanceUnavailableException}, jamais de
 * frais supposé. Pas de cache ici : {@code TradingFeeService} (24 h).
 */
@Slf4j
@Component
public class OneTradingTradingFeeProvider implements TradingFeeProvider {

    static final String FEES_PATH = "/account/fees";
    private static final String SPOT_GROUP = "SPOT";
    private static final Duration MIN_INTERVAL = Duration.ofMillis(500);
    private final DomainClock clock;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public OneTradingTradingFeeProvider(DomainClock clock) {
        this(clock, MIN_INTERVAL);
    }

    OneTradingTradingFeeProvider(DomainClock clock, Duration minInterval) {
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
        return WebProviderCode.ONE_TRADING;
    }

    @Override
    public FeeRate getFeeRate(ApiCredential credential, String instId) {
        OneTradingRequests.requireComplete(credential);
        throttle();
        FeeRate rate = parse(OneTradingRequests.get(credential, FEES_PATH));
        log.debug("📦 [ONE_TRADING] frais du compte lus : maker={}% taker={}%", rate.makerPct(), rate.takerPct());
        return rate;
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BalanceUnavailableException("One Trading : attente interrompue", e);
            }
        }
        lastCallNanos = System.nanoTime();
    }

    /** Package-private pour les tests. */
    static FeeRate parse(OneTradingRequests.Reply reply) {
        JsonNode root = OneTradingRequests.requireJson(reply, "account/fees");
        for (JsonNode tier : root.path("active_fee_tiers")) {
            if (SPOT_GROUP.equalsIgnoreCase(tier.path("fee_group_id").asText())) {
                return new FeeRate(percent(tier, "maker_fee"), percent(tier, "taker_fee"));
            }
        }
        throw new BalanceUnavailableException("One Trading : palier de frais SPOT absent de la réponse");
    }

    private static BigDecimal percent(JsonNode tier, String field) {
        try {
            return new BigDecimal(tier.path(field).asText(""));
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("One Trading : " + field + " illisible dans la réponse account/fees", e);
        }
    }
}
