package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.RevolutXRequests;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.RevolutXRequests.Reply;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.RevolutXTrades;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Client d'ordres spot Revolut X (clé API de portée Trade + clé privée Ed25519, signature par
 * {@link RevolutXRequests}). <b>Bean inactif par défaut</b> : il n'existe que si {@code tradeio.execution.mode=LIVE} et
 * {@code tradeio.execution.live-unlocked=true} ({@link LiveExecutionCondition}).
 * <ul>
 *   <li>{@code POST /1.0/orders} : {@code order_configuration.limit} avec {@code base_size}, {@code price}, {@code time_in_force=ioc}
 *       et {@code execution_instructions=["allow_taker"]} (un IOC est preneur) ; jamais d'ordre au marché.</li>
 *   <li>Pas de lecture par identifiant client : {@code GET /1.0/orders/active} puis {@code GET /1.0/orders/historical}
 *       (2 derniers jours, symbole de l'ordre) sont parcourus pour retrouver {@code client_order_id}.
 *       {@code DELETE /1.0/orders/{id}} annule ; {@code GET /1.0/orders/fills/{id}} + {@code GET /1.0/orders/{id}} (frais) lisent les
 *       remplissages.</li>
 * </ul>
 * Le {@code client_order_id} est un UUID dérivé de façon déterministe du {@code clOrdId} interne ({@link #clientId}) : on peut relire
 * et annuler sans rien stocker. Erreurs : rejet métier certain (HTTP 400) {@link OrderRejectedException}, clé refusée (401 / 403)
 * {@link TradeCredentialRejectedException}, tout le reste (409, 429, 5xx, délai, réponse illisible) {@link ExchangeUnavailableException}
 * (issue inconnue, jamais de renvoi). Jamais de log des en-têtes d'authentification.
 */
@Slf4j
@Component
@Conditional(LiveExecutionCondition.class)
public class RevolutXSpotOrderClient implements SpotOrderPort {

    static final String ORDERS_PATH = "/1.0/orders";
    static final String ACTIVE_PATH = "/1.0/orders/active";
    static final String HISTORICAL_PATH = "/1.0/orders/historical";
    static final String FILLS_PATH = "/1.0/orders/fills/";

    private static final String CLIENT_ID_SEED = "t5:";
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9]+-[A-Z0-9]+");
    private static final Duration LOOKBACK = Duration.ofDays(2);
    private static final int ACTIVE_PAGE = 300;
    private static final int HISTORY_PAGE = 1900;
    private static final int MAX_PAGES = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public RevolutXSpotOrderClient(DomainClock clock) {
        this(clock, ExecutionDefaults.ORDER_CLIENT_MIN_INTERVAL);
    }

    RevolutXSpotOrderClient(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode provider() {
        return WebProviderCode.REVOLUT_X;
    }

    /** UUID déterministe (type 3) du {@code clOrdId} interne : même entrée => même {@code client_order_id}. */
    static String clientId(String clOrdId) {
        return UUID.nameUUIDFromBytes((CLIENT_ID_SEED + clOrdId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public OrderAck submit(ApiCredential credential, OrderRequest request) {
        requireComplete(credential);
        String symbol = symbolOf(request.instId());
        String body = orderBody(request, symbol);
        log.info("Revolut X ordre envoyé clOrdId={} {} {} sz={} px={}", request.clOrdId(), request.side(), symbol,
                request.sz().toPlainString(), request.px().toPlainString());
        Reply reply = call(HttpMethod.POST, credential, ORDERS_PATH, null, body, "envoi");
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("Revolut X : clé Trade refusée HTTP " + reply.status());
        }
        if (reply.status() == 400) {
            String message = RevolutXRequests.errorOf(reply.body());
            throw new OrderRejectedException(message.isBlank() ? "400" : message, message);
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("Revolut X : issue inconnue HTTP " + reply.status());
        }
        JsonNode ack = firstOf(parse(reply.body()));
        if ("rejected".equalsIgnoreCase(ack.path("state").asText())) {
            throw new OrderRejectedException("rejected", "ordre rejeté par Revolut X");
        }
        String venueId = ack.path("venue_order_id").asText("");
        if (venueId.isBlank()) {
            throw new ExchangeUnavailableException("Revolut X : réponse sans venue_order_id (l'ordre a pu partir)");
        }
        return new OrderAck(request.clOrdId(), venueId);
    }

    @Override
    public OrderState query(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        JsonNode order = find(credential, symbolOf(instId), clOrdId, true);
        if (order == null) {
            throw new OrderNotFoundException("Aucun ordre " + clOrdId + " sur " + instId);
        }
        BigDecimal filled = decimal(order, "filled_quantity", BigDecimal.ZERO);
        BigDecimal avg = filled.signum() > 0 ? decimal(order, "average_fill_price", null) : null;
        return new OrderState(clOrdId, order.path("id").asText(""),
                phaseOf(order.path("status").asText(""), filled, decimal(order, "quantity", null)), filled, avg);
    }

    @Override
    public void cancel(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        JsonNode order = find(credential, symbolOf(instId), clOrdId, false);
        String id = order == null ? "" : order.path("id").asText("");
        if (id.isBlank() || !PLAIN_ID.matcher(id).matches()) {
            throw new OrderNotFoundException("Revolut X : ordre actif introuvable");
        }
        Reply reply = call(HttpMethod.DELETE, credential, ORDERS_PATH + "/" + id, null, null, "annulation");
        if (reply.status() == 404) {
            throw new OrderNotFoundException("Revolut X : ordre introuvable");
        }
        requireReadable(reply, "annulation");
    }

    @Override
    public List<Fill> fills(ApiCredential credential, String instId, String ordId) {
        requireComplete(credential);
        if (ordId == null || !PLAIN_ID.matcher(ordId).matches()) {
            throw new ExchangeUnavailableException("Revolut X : identifiant d'ordre invalide");
        }
        Reply detailsReply = call(HttpMethod.GET, credential, ORDERS_PATH + "/" + ordId, null, null, "lecture de l'ordre");
        requireReadable(detailsReply, "lecture de l'ordre");
        JsonNode details = firstOf(parse(detailsReply.body()));
        Reply fillsReply = call(HttpMethod.GET, credential, FILLS_PATH + ordId, null, null, "lecture des remplissages");
        requireReadable(fillsReply, "lecture des remplissages");
        BigDecimal fee = totalFee(details);
        String feeCcy = details.path("fee_currency").asText("");
        String clOrdId = details.path("client_order_id").asText("");
        List<Fill> out = new ArrayList<>();
        boolean first = true;
        try {
            for (RevolutXTrades.Trade trade : RevolutXTrades.parse(RevolutXRequests.dataOf(parse(fillsReply.body())))) {
                String side = trade.side().isBlank() ? details.path("side").asText() : trade.side();
                out.add(new Fill(trade.tradeId(), ordId, clOrdId, instId, "sell".equalsIgnoreCase(side) ? StepSide.SELL : StepSide.BUY,
                        trade.price(), trade.quantity(), first ? fee : BigDecimal.ZERO, feeCcy, trade.time()));
                first = false;
            }
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException(e.getMessage(), e);
        }
        return out;
    }

    // ------------------------------------------------------------------ corps et recherche

    /** Corps exact d'un ordre, minifié (c'est le texte signé) : montants en chaînes, ordre des clés stable. */
    static String orderBody(OrderRequest request, String symbol) {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("base_size", request.sz().toPlainString());
        limit.put("price", request.px().toPlainString());
        limit.put("time_in_force", "ioc");
        limit.put("execution_instructions", List.of("allow_taker"));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("limit", limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_order_id", clientId(request.clOrdId()));
        payload.put("symbol", symbol);
        payload.put("side", request.side() == StepSide.BUY ? "buy" : "sell");
        payload.put("order_configuration", configuration);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corps d'ordre non sérialisable", e);
        }
    }

    /**
     * Retrouve l'ordre portant le {@code client_order_id} dérivé : ordres actifs d'abord, puis (si {@code withHistory}) l'historique
     * des 2 derniers jours. {@code null} si introuvable.
     */
    private JsonNode find(ApiCredential credential, String symbol, String clOrdId, boolean withHistory) {
        String wanted = clientId(clOrdId);
        JsonNode active = scan(credential, ACTIVE_PATH, "symbols=" + symbol + "&limit=" + ACTIVE_PAGE, wanted);
        if (active != null || !withHistory) {
            return active;
        }
        long end = clock.now().toEpochMilli();
        return scan(credential, HISTORICAL_PATH, "symbols=" + symbol + "&start_date=" + (end - LOOKBACK.toMillis()) + "&end_date=" + end
                + "&limit=" + HISTORY_PAGE, wanted);
    }

    private JsonNode scan(ApiCredential credential, String path, String query, String wanted) {
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Reply reply = call(HttpMethod.GET, credential, path, query + (cursor == null ? "" : "&cursor=" + cursor), null, "lecture");
            requireReadable(reply, "lecture");
            JsonNode root = parse(reply.body());
            for (JsonNode order : RevolutXRequests.dataOf(root)) {
                if (wanted.equalsIgnoreCase(order.path("client_order_id").asText())) {
                    return order;
                }
            }
            String next = root.path("metadata").path("next_cursor").asText("");
            if (next.isBlank()) {
                return null;
            }
            cursor = next;
        }
        throw new ExchangeUnavailableException("Revolut X : trop de pages pour retrouver l'ordre");
    }

    // ------------------------------------------------------------------ transport

    private Reply call(HttpMethod method, ApiCredential credential, String path, String query, String body, String what) {
        throttle();
        try {
            return RevolutXRequests.send(method, credential, path, query, body);
        } catch (CredentialRejectedException e) {
            throw new TradeCredentialRejectedException("Revolut X : clé privée Ed25519 inutilisable");
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException("Revolut X : " + what + " impossible (" + e.getMessage() + ")", e);
        }
    }

    private static void requireReadable(Reply reply, String what) {
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("Revolut X : clé Trade refusée HTTP " + reply.status());
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("Revolut X : " + what + " en erreur HTTP " + reply.status());
        }
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExchangeUnavailableException("Revolut X : attente interrompue");
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private static void requireComplete(ApiCredential credential) {
        try {
            RevolutXRequests.requireComplete(credential);
        } catch (BalanceUnavailableException e) {
            throw new TradeCredentialRejectedException("Credential TRADE incomplète ou provider Revolut X désactivé");
        }
    }

    private static String symbolOf(String instId) {
        String symbol = instId == null ? "" : instId.toUpperCase();
        if (!SYMBOL.matcher(symbol).matches()) {
            throw new ExchangeUnavailableException("Revolut X : instrument invalide");
        }
        return symbol;
    }

    // ------------------------------------------------------------------ réponses

    private static JsonNode parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !(root.isObject() || root.isArray())) {
                throw new ExchangeUnavailableException("Revolut X : réponse inattendue (" + RevolutXRequests.abbreviate(body) + ")");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ExchangeUnavailableException("Revolut X : réponse illisible (" + RevolutXRequests.abbreviate(body) + ")", e);
        }
    }

    /** Premier élément de {@code data} (objet unique ou tableau d'un élément selon la route). */
    private static JsonNode firstOf(JsonNode root) {
        JsonNode data = RevolutXRequests.dataOf(root);
        if (!data.isArray() || data.isEmpty() || !data.get(0).isObject()) {
            throw new ExchangeUnavailableException("Revolut X : réponse sans données");
        }
        return data.get(0);
    }

    /**
     * Statuts de la doc : {@code pending_new}, {@code new} = vivant ; {@code partially_filled} = partiel encore actif ;
     * {@code filled} (ou quantité remplie = quantité) = rempli ; {@code cancelled}, {@code rejected} = terminé non rempli (le
     * solde d'un IOC partiel est annulé, la quantité remplie reste lue séparément). {@code replaced} ou toute autre valeur =
     * {@code UNKNOWN} : réconciliation, jamais de renvoi.
     */
    static OrderPhase phaseOf(String status, BigDecimal filled, BigDecimal quantity) {
        String s = status == null ? "" : status.toLowerCase();
        if ("filled".equals(s) || (quantity != null && quantity.signum() > 0 && filled.compareTo(quantity) >= 0)) {
            return OrderPhase.FILLED;
        }
        return switch (s) {
            case "pending_new", "new" -> filled.signum() > 0 ? OrderPhase.PARTIALLY_FILLED : OrderPhase.LIVE;
            case "partially_filled" -> OrderPhase.PARTIALLY_FILLED;
            case "cancelled", "rejected" -> OrderPhase.CANCELED;
            default -> OrderPhase.UNKNOWN;
        };
    }

    private static BigDecimal totalFee(JsonNode details) {
        try {
            return RevolutXTrades.totalFee(details);
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException(e.getMessage(), e);
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
            throw new ExchangeUnavailableException("Revolut X : champ " + field + " illisible");
        }
    }
}
