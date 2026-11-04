package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.BitvavoRequests;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.BitvavoRequests.Reply;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Client d'ordres spot Bitvavo (clé API avec le droit « Trader », signature HMAC-SHA256 : {@link BitvavoRequests}). <b>Bean
 * inactif par défaut</b> : il n'existe que si {@code tradeio.execution.mode=LIVE} et {@code tradeio.execution.live-unlocked=true}
 * ({@link LiveExecutionCondition}).
 * <ul>
 *   <li>{@code POST /order} : {@code orderType=limit}, {@code timeInForce=IOC}, {@code amount} en base, {@code price} plafond,
 *       {@code clientOrderId}, {@code operatorId} (obligatoire) ; jamais d'ordre au marché.</li>
 *   <li>{@code GET /order?market=&clientOrderId=}, {@code DELETE /order?market=&clientOrderId=&operatorId=}, remplissages =
 *       tableau {@code fills} de {@code GET /order?market=&orderId=}.</li>
 * </ul>
 * Bitvavo exige un <b>UUID</b> comme {@code clientOrderId} : dérivé de façon déterministe du {@code clOrdId} interne
 * ({@link ClientOrderUuids}). Erreurs : rejet métier certain (HTTP 400 / 409) {@link OrderRejectedException} (code = {@code
 * errorCode} Bitvavo), doublon de {@code clientOrderId} (220) = {@link OrderRejectedException#duplicateClientOrderId()} (l'ordre
 * existe, à relire), clé refusée (HTTP 403, famille 3xx) {@link TradeCredentialRejectedException}, ordre absent (240)
 * {@link OrderNotFoundException}, tout le reste (429, 5xx dont 109 « timeout », délai, réponse illisible)
 * {@link ExchangeUnavailableException} (issue inconnue, jamais de renvoi). Jamais de log des en-têtes d'authentification.
 */
@Slf4j
@Component
@Conditional(LiveExecutionCondition.class)
public class BitvavoSpotOrderClient implements SpotOrderPort {

    static final String ORDER_PATH = "/order";
    /** Identifiant du « trader ou bot » exigé par l'API sur les ordres et annulations : constant, c'est TradeIO5. */
    static final long OPERATOR_ID = 5L;
    /** {@code errorCode} Bitvavo : doublon de {@code clientOrderId} dans le marché. */
    static final int ERR_DUPLICATE_CLIENT_ORDER_ID = 220;
    /** {@code errorCode} Bitvavo : ordre inexistant ou plus actif. */
    static final int ERR_ORDER_NOT_FOUND = 240;

    private static final Pattern MARKET = Pattern.compile("[A-Z0-9]+-[A-Z0-9]+");
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int PRICE_SCALE = 12;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public BitvavoSpotOrderClient(DomainClock clock) {
        this(clock, ExecutionDefaults.ORDER_CLIENT_MIN_INTERVAL);
    }

    BitvavoSpotOrderClient(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode provider() {
        return WebProviderCode.BITVAVO;
    }

    /** UUID déterministe du {@code clOrdId} interne. */
    static String clientOrderId(String clOrdId) {
        return ClientOrderUuids.of(clOrdId);
    }

    @Override
    public OrderAck submit(ApiCredential credential, OrderRequest request) {
        requireComplete(credential);
        String body = orderBody(request);
        log.info("Bitvavo ordre envoyé clOrdId={} {} {} sz={} px={}", request.clOrdId(), request.side(), request.instId(),
                request.sz().toPlainString(), request.px().toPlainString());
        Reply reply = call(HttpMethod.POST, credential, ORDER_PATH, body, "envoi");
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("Bitvavo : clé Trade refusée HTTP " + reply.status() + " code " + reply.errorCode());
        }
        if (reply.errorCode() == ERR_DUPLICATE_CLIENT_ORDER_ID) {
            throw new OrderRejectedException(OrderRejectedException.DUPLICATE_CL_ORD_ID, "clientOrderId déjà utilisé");
        }
        if (reply.status() == 400 || reply.status() == HTTP_CONFLICT) {
            String message = BitvavoRequests.errorOf(reply.body());
            throw new OrderRejectedException(reply.errorCode() >= 0 ? String.valueOf(reply.errorCode()) : String.valueOf(reply.status()),
                    message);
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("Bitvavo : issue inconnue HTTP " + reply.status());
        }
        return new OrderAck(request.clOrdId(), parse(reply.body()).path("orderId").asText(""));
    }

    @Override
    public OrderState query(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        Reply reply = call(HttpMethod.GET, credential, ORDER_PATH + "?market=" + market(instId) + "&clientOrderId="
                + clientOrderId(clOrdId), null, "lecture");
        requireFound(reply, "Aucun ordre " + clOrdId + " sur " + instId);
        requireReadable(reply, "lecture");
        JsonNode order = parse(reply.body());
        BigDecimal filled = decimal(order, "filledAmount", BigDecimal.ZERO);
        BigDecimal quote = decimal(order, "filledAmountQuote", null);
        BigDecimal avg = filled.signum() > 0 && quote != null ? quote.divide(filled, PRICE_SCALE, RoundingMode.HALF_UP) : null;
        return new OrderState(clOrdId, order.path("orderId").asText(""),
                phaseOf(order.path("status").asText(""), filled, decimal(order, "amount", null)), filled, avg);
    }

    @Override
    public void cancel(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        Reply reply = call(HttpMethod.DELETE, credential, ORDER_PATH + "?market=" + market(instId) + "&clientOrderId="
                + clientOrderId(clOrdId) + "&operatorId=" + OPERATOR_ID, null, "annulation");
        requireFound(reply, "Bitvavo : ordre introuvable");
        requireReadable(reply, "annulation");
    }

    @Override
    public List<Fill> fills(ApiCredential credential, String instId, String ordId) {
        requireComplete(credential);
        if (ordId == null || !PLAIN_ID.matcher(ordId).matches()) {
            throw new ExchangeUnavailableException("Bitvavo : identifiant d'ordre invalide");
        }
        Reply reply = call(HttpMethod.GET, credential, ORDER_PATH + "?market=" + market(instId) + "&orderId=" + ordId, null,
                "lecture des remplissages");
        requireFound(reply, "Bitvavo : ordre introuvable " + ordId);
        requireReadable(reply, "lecture des remplissages");
        JsonNode order = parse(reply.body());
        StepSide side = "sell".equalsIgnoreCase(order.path("side").asText()) ? StepSide.SELL : StepSide.BUY;
        List<Fill> out = new ArrayList<>();
        for (JsonNode row : order.path("fills")) {
            String feeCcy = row.path("feeCurrency").asText(order.path("feeCurrency").asText(""));
            out.add(new Fill(row.path("id").asText(), ordId, "", instId, side, decimal(row, "price", BigDecimal.ZERO),
                    decimal(row, "amount", BigDecimal.ZERO), decimal(row, "fee", BigDecimal.ZERO), feeCcy,
                    instantOf(row.path("timestamp").asLong(0))));
        }
        return out;
    }

    // ------------------------------------------------------------------ corps et transport

    /** Corps exact d'un ordre : montants en chaînes, ordre des clés stable. */
    static String orderBody(OrderRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("market", market(request.instId()));
        payload.put("side", request.side() == StepSide.BUY ? "buy" : "sell");
        payload.put("orderType", "limit");
        payload.put("operatorId", OPERATOR_ID);
        payload.put("clientOrderId", clientOrderId(request.clOrdId()));
        payload.put("amount", request.sz().toPlainString());
        payload.put("price", request.px().toPlainString());
        payload.put("timeInForce", "IOC");
        payload.put("responseRequired", true);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corps d'ordre non sérialisable", e);
        }
    }

    private static String market(String instId) {
        String market = instId == null ? "" : instId.toUpperCase();
        if (!MARKET.matcher(market).matches()) {
            throw new ExchangeUnavailableException("Bitvavo : marché invalide");
        }
        return market;
    }

    private Reply call(HttpMethod method, ApiCredential credential, String path, String body, String what) {
        throttle();
        try {
            return BitvavoRequests.send(method, credential, path, body);
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException("Bitvavo : " + what + " impossible (" + e.getMessage() + ")", e);
        }
    }

    private static void requireFound(Reply reply, String message) {
        if (reply.status() == HTTP_NOT_FOUND || reply.errorCode() == ERR_ORDER_NOT_FOUND) {
            throw new OrderNotFoundException(message);
        }
    }

    private static void requireReadable(Reply reply, String what) {
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("Bitvavo : clé Trade refusée HTTP " + reply.status() + " code " + reply.errorCode());
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("Bitvavo : " + what + " en erreur HTTP " + reply.status());
        }
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExchangeUnavailableException("Bitvavo : attente interrompue");
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private static void requireComplete(ApiCredential credential) {
        try {
            BitvavoRequests.requireComplete(credential);
        } catch (BalanceUnavailableException e) {
            throw new TradeCredentialRejectedException("Credential TRADE incomplète ou provider Bitvavo désactivé");
        }
    }

    // ------------------------------------------------------------------ réponses

    private static JsonNode parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ExchangeUnavailableException("Bitvavo : réponse inattendue (" + BitvavoRequests.abbreviate(body) + ")");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ExchangeUnavailableException("Bitvavo : réponse illisible (" + BitvavoRequests.abbreviate(body) + ")", e);
        }
    }

    /**
     * Statuts documentés : {@code new} / {@code awaitingTrigger} = vivant, {@code partiallyFilled} = partiellement rempli encore
     * actif, {@code filled} (ou quantité remplie = quantité) = rempli, {@code canceled} / {@code expired} = terminé (le solde d'un
     * IOC partiel est annulé, la quantité remplie reste lue séparément). Toute autre valeur = {@code UNKNOWN} : réconciliation,
     * jamais de renvoi.
     */
    static OrderPhase phaseOf(String status, BigDecimal filled, BigDecimal amount) {
        String s = status == null ? "" : status;
        if ("filled".equals(s) || (amount != null && amount.signum() > 0 && filled.compareTo(amount) >= 0)) {
            return OrderPhase.FILLED;
        }
        return switch (s) {
            case "new", "awaitingTrigger" -> filled.signum() > 0 ? OrderPhase.PARTIALLY_FILLED : OrderPhase.LIVE;
            case "partiallyFilled" -> OrderPhase.PARTIALLY_FILLED;
            case "canceled", "expired" -> OrderPhase.CANCELED;
            default -> OrderPhase.UNKNOWN;
        };
    }

    private Instant instantOf(long millis) {
        return millis > 0 ? Instant.ofEpochMilli(millis) : clock.now();
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String text = node.path(field).asText("");
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new ExchangeUnavailableException("Bitvavo : champ " + field + " illisible");
        }
    }
}
