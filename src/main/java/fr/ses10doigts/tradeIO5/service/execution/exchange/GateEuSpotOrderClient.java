package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.GateEuRequests;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.GateEuRequests.Reply;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Client d'ordres spot Gate EU (clé API de portée Trade, signature HMAC-SHA512). <b>Bean inactif par défaut</b> : il n'existe
 * que si {@code tradeio.execution.mode=LIVE} et {@code tradeio.execution.live-unlocked=true} ({@link LiveExecutionCondition}).
 * <ul>
 *   <li>{@code POST /spot/orders} : {@code type=limit}, {@code time_in_force=ioc}, {@code amount} en base, {@code price}
 *       plafond, {@code text} = {@code t-<clOrdId>} ; jamais d'ordre au marché.</li>
 *   <li>Lecture : {@code GET /spot/orders/{text}?currency_pair=} tant que l'ordre est en attente ; la doc réserve l'identifiant
 *       client aux ordres en attente et un IOC est terminé aussitôt : repli sur {@code GET /spot/orders?status=finished} filtré
 *       par {@code text} (7 jours, {@value #FINISHED_PAGES} pages de {@value #PAGE_SIZE} au plus).</li>
 *   <li>Annulation : {@code DELETE /spot/orders/{text}?currency_pair=}. Remplissages : {@code GET /spot/my_trades?order_id=}
 *       (identifiant système de l'ordre).</li>
 * </ul>
 * {@code text} doit commencer par {@code t-} et faire 28 octets au plus après le préfixe : le {@code clOrdId} interne est repris
 * tel quel s'il tient, sinon réduit de façon déterministe ({@link #textOf}). Transport mutualisé avec les lecteurs
 * ({@link GateEuRequests}). Erreurs : rejet métier certain (HTTP 400 / 422) {@link OrderRejectedException} (un libellé de doublon
 * = {@link OrderRejectedException#duplicateClientOrderId()}, l'ordre existe, à relire), clé refusée (401 / 403)
 * {@link TradeCredentialRejectedException}, tout le reste (429, 5xx, délai, réponse illisible) {@link ExchangeUnavailableException}
 * (issue inconnue, jamais de renvoi). Jamais de log des en-têtes d'authentification.
 */
@Slf4j
@Component
@Conditional(LiveExecutionCondition.class)
public class GateEuSpotOrderClient implements SpotOrderPort {

    static final String ORDERS_PATH = "/spot/orders";
    static final String TRADES_PATH = "/spot/my_trades";
    static final String TEXT_PREFIX = "t-";
    static final int TEXT_MAX = 28;
    static final int PAGE_SIZE = 100;
    static final int FINISHED_PAGES = 5;

    private static final Duration FINISHED_LOOKBACK = Duration.ofDays(7);
    private static final Pattern NATIVE_PAIR = Pattern.compile("[A-Z0-9]+_[A-Z0-9]+");
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final int HTTP_NOT_FOUND = 404;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public GateEuSpotOrderClient(DomainClock clock) {
        this(clock, ExecutionDefaults.ORDER_CLIENT_MIN_INTERVAL);
    }

    GateEuSpotOrderClient(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode provider() {
        return WebProviderCode.GATE_EU;
    }

    /** {@code t-<clOrdId>} si ça tient dans 28 octets, sinon les 28 premiers hexadécimaux de SHA-256 : déterministe. */
    static String textOf(String clOrdId) {
        if (clOrdId.length() <= TEXT_MAX) {
            return TEXT_PREFIX + clOrdId;
        }
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clOrdId.getBytes(StandardCharsets.UTF_8)));
            return TEXT_PREFIX + hex.substring(0, TEXT_MAX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    @Override
    public OrderAck submit(ApiCredential credential, OrderRequest request) {
        requireComplete(credential);
        String body = orderBody(request);
        log.info("Gate EU ordre envoyé clOrdId={} {} {} sz={} px={}", request.clOrdId(), request.side(), request.instId(),
                request.sz().toPlainString(), request.px().toPlainString());
        Reply reply = call(HttpMethod.POST, credential, ORDERS_PATH, body, "envoi");
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("Gate EU : clé Trade refusée HTTP " + reply.status());
        }
        if (reply.status() == 400 || reply.status() == 422) {
            String label = reply.label();
            if (isDuplicate(label)) {
                throw new OrderRejectedException(OrderRejectedException.DUPLICATE_CL_ORD_ID, "text déjà utilisé");
            }
            String message = GateEuRequests.errorOf(reply.body());
            throw new OrderRejectedException(label.isBlank() ? String.valueOf(reply.status()) : label, message);
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("Gate EU : issue inconnue HTTP " + reply.status());
        }
        return new OrderAck(request.clOrdId(), parse(reply.body()).path("id").asText(""));
    }

    @Override
    public OrderState query(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        String pair = pairOf(instId);
        String text = textOf(clOrdId);
        Reply reply = call(HttpMethod.GET, credential, ORDERS_PATH + "/" + text + "?currency_pair=" + pair, null, "lecture");
        if (reply.status() != HTTP_NOT_FOUND) {
            requireReadable(reply, "lecture");
            return stateOf(clOrdId, parse(reply.body()));
        }
        long from = clock.now().minus(FINISHED_LOOKBACK).getEpochSecond();
        for (int page = 1; page <= FINISHED_PAGES; page++) {
            Reply finished = call(HttpMethod.GET, credential, ORDERS_PATH + "?currency_pair=" + pair + "&status=finished&from=" + from
                    + "&limit=" + PAGE_SIZE + "&page=" + page, null, "lecture des ordres terminés");
            requireReadable(finished, "lecture des ordres terminés");
            JsonNode rows = parseAny(finished.body());
            if (!rows.isArray()) {
                throw new ExchangeUnavailableException("Gate EU : liste d'ordres inattendue");
            }
            for (JsonNode row : rows) {
                if (text.equals(row.path("text").asText())) {
                    return stateOf(clOrdId, row);
                }
            }
            if (rows.size() < PAGE_SIZE) {
                break;
            }
        }
        throw new OrderNotFoundException("Aucun ordre " + clOrdId + " sur " + instId);
    }

    @Override
    public void cancel(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        Reply reply = call(HttpMethod.DELETE, credential, ORDERS_PATH + "/" + textOf(clOrdId) + "?currency_pair=" + pairOf(instId),
                null, "annulation");
        if (reply.status() == HTTP_NOT_FOUND) {
            throw new OrderNotFoundException("Gate EU : ordre introuvable ou déjà terminé");
        }
        requireReadable(reply, "annulation");
    }

    @Override
    public List<Fill> fills(ApiCredential credential, String instId, String ordId) {
        requireComplete(credential);
        if (ordId == null || !PLAIN_ID.matcher(ordId).matches()) {
            throw new ExchangeUnavailableException("Gate EU : identifiant d'ordre invalide");
        }
        Reply reply = call(HttpMethod.GET, credential, TRADES_PATH + "?currency_pair=" + pairOf(instId) + "&order_id=" + ordId
                + "&limit=" + PAGE_SIZE, null, "lecture des remplissages");
        requireReadable(reply, "lecture des remplissages");
        JsonNode rows = parseAny(reply.body());
        if (!rows.isArray()) {
            throw new ExchangeUnavailableException("Gate EU : liste de remplissages inattendue");
        }
        List<Fill> out = new ArrayList<>();
        for (JsonNode row : rows) {
            out.add(new Fill(row.path("id").asText(), row.path("order_id").asText(ordId), "", instId,
                    "buy".equalsIgnoreCase(row.path("side").asText()) ? StepSide.BUY : StepSide.SELL,
                    decimal(row, "price", BigDecimal.ZERO), decimal(row, "amount", BigDecimal.ZERO),
                    decimal(row, "fee", BigDecimal.ZERO).abs(), row.path("fee_currency").asText("").toUpperCase(), instantOf(row)));
        }
        return out;
    }

    // ------------------------------------------------------------------ corps et transport

    /** Corps exact d'un ordre : montants en chaînes, ordre des clés stable. */
    static String orderBody(OrderRequest request) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("text", textOf(request.clOrdId()));
        payload.put("currency_pair", GateEuRequests.nativePair(request.instId()));
        payload.put("type", "limit");
        payload.put("account", "spot");
        payload.put("side", request.side() == StepSide.BUY ? "buy" : "sell");
        payload.put("amount", request.sz().toPlainString());
        payload.put("price", request.px().toPlainString());
        payload.put("time_in_force", "ioc");
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corps d'ordre non sérialisable", e);
        }
    }

    /** Heuristique : la doc ne liste pas le libellé d'un {@code text} déjà utilisé. */
    private static boolean isDuplicate(String label) {
        String upper = label.toUpperCase();
        return upper.contains("DUPLICATE") || upper.contains("ORDER_EXISTS") || upper.contains("TEXT_EXIST");
    }

    private static String pairOf(String instId) {
        String pair = GateEuRequests.nativePair(instId);
        if (!NATIVE_PAIR.matcher(pair).matches()) {
            throw new ExchangeUnavailableException("Gate EU : instrument invalide");
        }
        return pair;
    }

    private Reply call(HttpMethod method, ApiCredential credential, String path, String body, String what) {
        throttle();
        try {
            return GateEuRequests.send(method, credential, path, body);
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException("Gate EU : " + what + " impossible (" + e.getMessage() + ")", e);
        }
    }

    private static void requireReadable(Reply reply, String what) {
        if (reply.credentialRefused()) {
            throw new TradeCredentialRejectedException("Gate EU : clé Trade refusée HTTP " + reply.status());
        }
        if (!reply.ok()) {
            throw new ExchangeUnavailableException("Gate EU : " + what + " en erreur HTTP " + reply.status());
        }
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExchangeUnavailableException("Gate EU : attente interrompue");
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private static void requireComplete(ApiCredential credential) {
        try {
            GateEuRequests.requireComplete(credential);
        } catch (BalanceUnavailableException e) {
            throw new TradeCredentialRejectedException("Credential TRADE incomplète ou provider Gate EU désactivé");
        }
    }

    // ------------------------------------------------------------------ réponses

    private static JsonNode parse(String body) {
        JsonNode root = parseAny(body);
        if (!root.isObject()) {
            throw new ExchangeUnavailableException("Gate EU : réponse inattendue (" + GateEuRequests.abbreviate(body) + ")");
        }
        return root;
    }

    private static JsonNode parseAny(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new ExchangeUnavailableException("Gate EU : réponse vide");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ExchangeUnavailableException("Gate EU : réponse illisible (" + GateEuRequests.abbreviate(body) + ")", e);
        }
    }

    private static OrderState stateOf(String clOrdId, JsonNode order) {
        BigDecimal amount = decimal(order, "amount", null);
        BigDecimal filled = order.hasNonNull("filled_amount") ? decimal(order, "filled_amount", BigDecimal.ZERO)
                : amount != null && order.hasNonNull("left") ? amount.subtract(decimal(order, "left", amount)) : BigDecimal.ZERO;
        BigDecimal avg = filled.signum() > 0 ? decimal(order, "avg_deal_price", null) : null;
        return new OrderState(clOrdId, order.path("id").asText(""),
                phaseOf(order.path("status").asText(""), order.path("finish_as").asText(""), filled, amount), filled, avg);
    }

    /**
     * {@code status} : {@code open} (à remplir), {@code closed} (rempli), {@code cancelled}. Quantité remplie = quantité (ou
     * {@code finish_as=filled}) = rempli ; {@code open} = vivant (partiel si de la quantité est déjà remplie) ; {@code closed}
     * ou {@code cancelled} sans remplissage complet = terminé non rempli (le solde d'un IOC partiel est annulé, la quantité
     * remplie reste lue séparément). Toute autre valeur = {@code UNKNOWN} : réconciliation, jamais de renvoi.
     */
    static OrderPhase phaseOf(String status, String finishAs, BigDecimal filled, BigDecimal amount) {
        if ("filled".equalsIgnoreCase(finishAs) || (amount != null && amount.signum() > 0 && filled.compareTo(amount) >= 0)) {
            return OrderPhase.FILLED;
        }
        return switch (status == null ? "" : status.toLowerCase()) {
            case "open" -> filled.signum() > 0 ? OrderPhase.PARTIALLY_FILLED : OrderPhase.LIVE;
            case "closed", "cancelled" -> OrderPhase.CANCELED;
            default -> OrderPhase.UNKNOWN;
        };
    }

    private Instant instantOf(JsonNode node) {
        String ms = node.path("create_time_ms").asText("");
        try {
            if (!ms.isBlank()) {
                return Instant.ofEpochMilli(new BigDecimal(ms).longValue());
            }
            String s = node.path("create_time").asText("");
            return s.isBlank() ? clock.now() : Instant.ofEpochSecond(new BigDecimal(s).longValue());
        } catch (NumberFormatException e) {
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
            throw new ExchangeUnavailableException("Gate EU : champ " + field + " illisible");
        }
    }
}
