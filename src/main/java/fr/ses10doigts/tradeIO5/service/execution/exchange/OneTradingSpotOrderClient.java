package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.OneTradingRequests;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.OneTradingRequests.Reply;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Client d'ordres spot One Trading (clé API de portée Trade, {@code Authorization: Bearer}). <b>Bean inactif par défaut</b> :
 * il n'existe que si {@code tradeio.execution.mode=LIVE} et {@code tradeio.execution.live-unlocked=true}
 * ({@link LiveExecutionCondition}).
 * <ul>
 *   <li>{@code POST /account/orders} : {@code type=LIMIT}, {@code time_in_force=IMMEDIATE_OR_CANCELLED}, {@code amount} en base,
 *       {@code price} plafond, {@code client_id} ; jamais d'ordre au marché.</li>
 *   <li>{@code GET /account/orders/client/{client_id}}, {@code DELETE /account/orders/client/{client_id}?instrument_code=},
 *       {@code GET /account/orders/{order_id}/trades}.</li>
 * </ul>
 * One Trading exige un <b>UUID</b> comme {@code client_id} : il est dérivé de façon déterministe du {@code clOrdId} interne
 * ({@link #clientId}), ce qui permet de relire et d'annuler sans rien stocker. Transport mutualisé avec les lecteurs
 * ({@link OneTradingRequests}). Erreurs : rejet métier certain (HTTP 400 / 422) {@link OrderRejectedException}, doublon de
 * {@code client_id} (409) = {@link OrderRejectedException#duplicateClientOrderId()} (l'ordre existe, à relire), clé refusée
 * (401 / 403) {@link TradeCredentialRejectedException}, tout le reste (429, 5xx, délai, réponse illisible)
 * {@link ExchangeUnavailableException} (issue inconnue, jamais de renvoi). Jamais de log de l'en-tête d'authentification.
 * La doc ne liste pas les valeurs de {@code status} : voir {@link #phaseOf}.
 */
@Slf4j
@Component
@Conditional(LiveExecutionCondition.class)
public class OneTradingSpotOrderClient implements SpotOrderPort {

    static final String ORDERS_PATH = "/account/orders";
    static final String BY_CLIENT_PATH = "/account/orders/client/";

    private static final Pattern NATIVE_CODE = Pattern.compile("[A-Z0-9]+_[A-Z0-9]+");
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_NOT_FOUND = 404;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public OneTradingSpotOrderClient(DomainClock clock) {
        this(clock, ExecutionDefaults.ORDER_CLIENT_MIN_INTERVAL);
    }

    OneTradingSpotOrderClient(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode provider() {
        return WebProviderCode.ONE_TRADING;
    }

    /** UUID déterministe (type 3) du {@code clOrdId} interne : même entrée => même {@code client_id}. */
    static String clientId(String clOrdId) {
        return ClientOrderUuids.of(clOrdId);
    }

    @Override
    public OrderAck submit(ApiCredential credential, OrderRequest request) {
        requireComplete(credential);
        String body = orderBody(request);
        log.info("One Trading ordre envoyé clOrdId={} {} {} sz={} px={}", request.clOrdId(), request.side(), request.instId(),
                request.sz().toPlainString(), request.px().toPlainString());
        Reply reply = call(HttpMethod.POST, credential, ORDERS_PATH, body, "envoi");
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("One Trading : clé Trade refusée HTTP " + reply.status());
        }
        if (reply.status() == HTTP_CONFLICT) {
            throw new OrderRejectedException(OrderRejectedException.DUPLICATE_CL_ORD_ID, "client_id déjà utilisé");
        }
        if (reply.status() == 400 || reply.status() == 422) {
            String message = OneTradingRequests.errorOf(reply.body());
            throw new OrderRejectedException(message.isBlank() ? String.valueOf(reply.status()) : message, message);
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("One Trading : issue inconnue HTTP " + reply.status());
        }
        JsonNode order = parse(reply.body());
        return new OrderAck(request.clOrdId(), order.path("order_id").asText(""));
    }

    @Override
    public OrderState query(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        Reply reply = call(HttpMethod.GET, credential, BY_CLIENT_PATH + clientId(clOrdId), null, "lecture");
        if (reply.status() == HTTP_NOT_FOUND) {
            throw new OrderNotFoundException("Aucun ordre " + clOrdId + " sur " + instId);
        }
        requireReadable(reply, "lecture");
        JsonNode order = parse(reply.body()).path("order");
        if (!order.isObject()) {
            throw new ExchangeUnavailableException("One Trading : réponse sans ordre");
        }
        BigDecimal filled = decimal(order, "filled_amount", BigDecimal.ZERO);
        BigDecimal avg = filled.signum() > 0 ? decimal(order, "average_price", null) : null;
        return new OrderState(clOrdId, order.path("order_id").asText(""),
                phaseOf(order.path("status").asText(""), filled, decimal(order, "amount", null)), filled, avg);
    }

    @Override
    public void cancel(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        String code = OneTradingRequests.nativeCode(instId);
        if (!NATIVE_CODE.matcher(code).matches()) {
            throw new ExchangeUnavailableException("One Trading : instrument invalide pour l'annulation");
        }
        Reply reply = call(HttpMethod.DELETE, credential, BY_CLIENT_PATH + clientId(clOrdId) + "?instrument_code=" + code, null,
                "annulation");
        if (reply.status() == HTTP_NOT_FOUND) {
            throw new OrderNotFoundException("One Trading : ordre introuvable");
        }
        requireReadable(reply, "annulation");
    }

    @Override
    public List<Fill> fills(ApiCredential credential, String instId, String ordId) {
        requireComplete(credential);
        if (ordId == null || !PLAIN_ID.matcher(ordId).matches()) {
            throw new ExchangeUnavailableException("One Trading : identifiant d'ordre invalide");
        }
        Reply reply = call(HttpMethod.GET, credential, ORDERS_PATH + "/" + ordId + "/trades", null, "lecture des remplissages");
        requireReadable(reply, "lecture des remplissages");
        List<Fill> out = new ArrayList<>();
        for (JsonNode row : parse(reply.body()).path("trade_history")) {
            JsonNode trade = row.path("trade");
            String[] parts = trade.path("instrument_code").asText("").split("_");
            BigDecimal fee = decimal(row.path("fee"), "fee_amount", BigDecimal.ZERO).abs();
            out.add(new Fill(trade.path("trade_id").asText(), trade.path("order_id").asText(ordId), "",
                    parts.length == 2 ? parts[0] + "-" + parts[1] : instId,
                    "BUY".equalsIgnoreCase(trade.path("side").asText()) ? StepSide.BUY : StepSide.SELL,
                    decimal(trade, "price", BigDecimal.ZERO), decimal(trade, "amount", BigDecimal.ZERO), fee,
                    row.path("fee").path("fee_currency").asText(""), instantOf(trade.path("time").asText(""))));
        }
        return out;
    }

    // ------------------------------------------------------------------ corps et transport

    /** Corps exact d'un ordre : montants en chaînes, ordre des clés stable. */
    static String orderBody(OrderRequest request) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instrument_code", OneTradingRequests.nativeCode(request.instId()));
        payload.put("type", "LIMIT");
        payload.put("side", request.side() == StepSide.BUY ? "BUY" : "SELL");
        payload.put("amount", request.sz().toPlainString());
        payload.put("price", request.px().toPlainString());
        payload.put("client_id", clientId(request.clOrdId()));
        payload.put("time_in_force", "IMMEDIATE_OR_CANCELLED");
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corps d'ordre non sérialisable", e);
        }
    }

    private Reply call(HttpMethod method, ApiCredential credential, String path, String body, String what) {
        throttle();
        try {
            return OneTradingRequests.send(method, credential, path, body);
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException("One Trading : " + what + " impossible (" + e.getMessage() + ")", e);
        }
    }

    private static void requireReadable(Reply reply, String what) {
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("One Trading : clé Trade refusée HTTP " + reply.status());
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("One Trading : " + what + " en erreur HTTP " + reply.status());
        }
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExchangeUnavailableException("One Trading : attente interrompue");
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private static void requireComplete(ApiCredential credential) {
        try {
            OneTradingRequests.requireComplete(credential);
        } catch (BalanceUnavailableException e) {
            throw new TradeCredentialRejectedException("Credential TRADE incomplète ou provider One Trading désactivé");
        }
    }

    // ------------------------------------------------------------------ réponses

    private static JsonNode parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ExchangeUnavailableException("One Trading : réponse inattendue (" + OneTradingRequests.abbreviate(body) + ")");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ExchangeUnavailableException("One Trading : réponse illisible (" + OneTradingRequests.abbreviate(body) + ")", e);
        }
    }

    /**
     * Les valeurs de {@code status} ne sont pas listées par la doc (seuls {@code OPEN}, {@code FILLED_FULLY} et {@code CANCELLED}
     * y figurent) : {@code OPEN} / {@code STOP_TRIGGERED} = vivant, {@code FILLED_FULLY} (ou quantité remplie = quantité) = rempli,
     * {@code FILLED} = partiellement rempli encore actif, {@code FILLED_CLOSED}, {@code FILLED_REJECTED}, {@code REJECTED},
     * {@code CLOSED}, {@code FAILED}, {@code CANCELLED} / {@code CANCELED} = terminé non rempli (le solde d'un IOC partiel est
     * annulé, la quantité remplie reste lue séparément). Toute autre valeur = {@code UNKNOWN} : réconciliation, jamais de renvoi.
     */
    static OrderPhase phaseOf(String status, BigDecimal filled, BigDecimal amount) {
        String s = status == null ? "" : status.toUpperCase();
        if ("FILLED_FULLY".equals(s) || (amount != null && amount.signum() > 0 && filled.compareTo(amount) >= 0)) {
            return OrderPhase.FILLED;
        }
        return switch (s) {
            case "OPEN", "STOP_TRIGGERED" -> filled.signum() > 0 ? OrderPhase.PARTIALLY_FILLED : OrderPhase.LIVE;
            case "FILLED" -> OrderPhase.PARTIALLY_FILLED;
            case "FILLED_CLOSED", "FILLED_REJECTED", "REJECTED", "CLOSED", "FAILED", "CANCELLED", "CANCELED" -> OrderPhase.CANCELED;
            default -> OrderPhase.UNKNOWN;
        };
    }

    private Instant instantOf(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return clock.now();
        }
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String text = node.path(field).asText("");
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new ExchangeUnavailableException("One Trading : champ " + field + " illisible");
        }
    }
}
