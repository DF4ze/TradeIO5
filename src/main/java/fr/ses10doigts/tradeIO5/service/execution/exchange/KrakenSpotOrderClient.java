package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.KrakenSignedRequests;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client d'ordres spot Kraken (clé Trade). <b>Bean inactif par défaut</b> : il n'existe que si
 * {@code tradeio.execution.mode=LIVE} et {@code tradeio.execution.live-unlocked=true} ({@link LiveExecutionCondition}). Il est
 * aussi le {@link SpotOrderValidator} de Kraken ({@code AddOrder validate=true} : aucun ordre créé).
 * <ul>
 *   <li>{@code AddOrder} : {@code ordertype=limit}, {@code timeinforce=IOC}, {@code volume} en base, {@code price} plafond,
 *       {@code oflags=fciq} (frais toujours en cotation : la devise du frais est alors connue sans ambiguïté), {@code pair} =
 *       nom natif lu au catalogue (jamais reconstruit ici) ; jamais d'ordre au marché.</li>
 *   <li>Identifiant client : Kraken n'accepte que 18 caractères ASCII ou un UUID de 32 hexadécimaux, alors que le
 *       {@code clOrdId} interne en compte jusqu'à 32 alphanumériques : {@code cl_ord_id} = les 32 premiers hexadécimaux de
 *       {@code SHA-256(clOrdId)} (déterministe : l'ordre se relit et s'annule par le même identifiant).</li>
 *   <li>Lecture d'un ordre : {@code ClosedOrders} puis {@code OpenOrders} filtrés par {@code cl_ord_id} (seul filtre documenté
 *       sans connaître le {@code txid}) ; remplissages : {@code QueryOrders trades=true} puis {@code QueryTrades}.</li>
 *   <li>Annulation : {@code CancelOrder} par {@code cl_ord_id}.</li>
 * </ul>
 * Signature et nonce via {@link KrakenSignedRequests}. Erreurs : {@code EOrder:*} = rejet métier certain
 * ({@link OrderRejectedException}) ; clé / signature / droit / IP refusés = {@link TradeCredentialRejectedException} ;
 * réseau, timeout, {@code EService:*}, {@code EAPI:Invalid nonce}, limite de débit, réponse illisible =
 * {@link ExchangeUnavailableException} (issue inconnue, jamais de renvoi). Aucun secret dans un log, une exception ou un DTO.
 */
@Slf4j
@Component
@Conditional(LiveExecutionCondition.class)
public class KrakenSpotOrderClient implements SpotOrderPort, SpotOrderValidator {

    static final String ADD_ORDER = "AddOrder";
    static final String CANCEL_ORDER = "CancelOrder";
    static final String OPEN_ORDERS = "OpenOrders";
    static final String CLOSED_ORDERS = "ClosedOrders";
    static final String QUERY_ORDERS = "QueryOrders";
    static final String QUERY_TRADES = "QueryTrades";

    /** Plafond documenté de {@code QueryTrades} : 20 identifiants par appel. */
    private static final int TRADES_PER_QUERY = 20;
    private static final int CLIENT_ID_HEX_LENGTH = 32;
    static final String PAIR_UNKNOWN = "PAIR_UNKNOWN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private enum Kind { CREDENTIAL, UNAVAILABLE, NOT_FOUND, REJECTED }

    private final DomainClock clock;
    private final InstrumentCatalog catalog;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public KrakenSpotOrderClient(DomainClock clock, InstrumentCatalog catalog) {
        this(clock, catalog, ExecutionDefaults.ORDER_CLIENT_MIN_INTERVAL);
    }

    KrakenSpotOrderClient(DomainClock clock, InstrumentCatalog catalog, Duration minInterval) {
        this.clock = clock;
        this.catalog = catalog;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode provider() {
        return WebProviderCode.KRAKEN;
    }

    // ------------------------------------------------------------------ SpotOrderPort

    @Override
    public OrderAck submit(ApiCredential credential, OrderRequest request) {
        requireComplete(credential);
        Map<String, String> params = orderParams(request);
        log.info("Kraken ordre envoyé clOrdId={} {} {} sz={} px={}", request.clOrdId(), request.side(), request.instId(),
                request.sz().toPlainString(), request.px().toPlainString());
        JsonNode root = call(credential, ADD_ORDER, params);
        checkErrors(root, true);
        JsonNode txid = root.path("result").path("txid");
        if (!txid.isArray() || txid.isEmpty() || txid.path(0).asText("").isBlank()) {
            throw new ExchangeUnavailableException("Kraken : AddOrder accepté sans txid lisible");
        }
        return new OrderAck(request.clOrdId(), txid.path(0).asText());
    }

    @Override
    public OrderState query(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        String krakenId = krakenClientId(clOrdId);
        Map<String, String> filter = Map.of("cl_ord_id", krakenId);
        JsonNode closed = readResult(credential, CLOSED_ORDERS, filter).path("closed");
        Map.Entry<String, JsonNode> found = first(closed);
        if (found == null) {
            found = first(readResult(credential, OPEN_ORDERS, filter).path("open"));
        }
        if (found == null) {
            throw new OrderNotFoundException("Aucun ordre " + clOrdId + " sur " + instId);
        }
        JsonNode order = found.getValue();
        BigDecimal vol = decimal(order, "vol", BigDecimal.ZERO);
        BigDecimal executed = decimal(order, "vol_exec", BigDecimal.ZERO);
        return new OrderState(clOrdId, found.getKey(), phaseOf(order.path("status").asText(""), vol, executed), executed,
                executed.signum() > 0 ? decimal(order, "price", null) : null);
    }

    @Override
    public void cancel(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        JsonNode root = call(credential, CANCEL_ORDER, Map.of("cl_ord_id", krakenClientId(clOrdId)));
        checkErrors(root, false);
        if (root.path("result").path("count").asInt(0) == 0 && !root.path("result").path("pending").asBoolean(false)) {
            throw new OrderNotFoundException("Kraken : aucun ordre ouvert à annuler pour " + clOrdId);
        }
    }

    @Override
    public List<Fill> fills(ApiCredential credential, String instId, String ordId) {
        requireComplete(credential);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("txid", ordId);
        params.put("trades", "true");
        JsonNode tradeIds = readResult(credential, QUERY_ORDERS, params).path(ordId).path("trades");
        List<String> ids = new ArrayList<>();
        tradeIds.forEach(id -> ids.add(id.asText()));
        List<Fill> out = new ArrayList<>();
        String feeCcy = quoteOf(instId);
        for (int from = 0; from < ids.size(); from += TRADES_PER_QUERY) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + TRADES_PER_QUERY));
            JsonNode trades = readResult(credential, QUERY_TRADES, Map.of("txid", String.join(",", chunk)));
            for (Iterator<Map.Entry<String, JsonNode>> it = trades.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode t = entry.getValue();
                BigDecimal seconds = decimal(t, "time", BigDecimal.valueOf(clock.now().getEpochSecond()));
                out.add(new Fill(entry.getKey(), t.path("ordertxid").asText(ordId), "", instId,
                        "buy".equalsIgnoreCase(t.path("type").asText()) ? StepSide.BUY : StepSide.SELL,
                        decimal(t, "price", BigDecimal.ZERO), decimal(t, "vol", BigDecimal.ZERO),
                        decimal(t, "fee", BigDecimal.ZERO), feeCcy,
                        Instant.ofEpochMilli(seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValue())));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ SpotOrderValidator

    @Override
    public ValidationReport validate(ApiCredential credential, OrderRequest request) {
        requireComplete(credential);
        Map<String, String> params;
        try {
            params = orderParams(request);
        } catch (OrderRejectedException e) {
            return ValidationReport.refused(e.getCode(), e.getReason(), null);
        }
        params.put("validate", "true");
        log.info("Kraken validation (aucun ordre créé) clOrdId={} {} {} sz={} px={}", request.clOrdId(), request.side(),
                request.instId(), request.sz().toPlainString(), request.px().toPlainString());
        JsonNode root = call(credential, ADD_ORDER, params);
        String raw = filtered(root);
        JsonNode errors = root.path("error");
        if (!errors.isArray() || errors.isEmpty()) {
            return ValidationReport.accepted(root.path("result").path("descr").path("order").asText(""), raw);
        }
        String error = errors.path(0).asText("");
        Kind kind = classify(error, true);
        if (kind == Kind.REJECTED) {
            return ValidationReport.refused(error, error, raw);
        }
        throwFor(kind, error);
        throw new IllegalStateException("inatteignable");
    }

    // ------------------------------------------------------------------ paramètres

    /** Identifiant client Kraken (UUID court) déterministe du {@code clOrdId} interne. */
    static String krakenClientId(String clOrdId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(clOrdId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, CLIENT_ID_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Paramètres exacts d'un ordre limit IOC (ordre des clés stable) ; la paire native vient du catalogue. */
    Map<String, String> orderParams(OrderRequest request) {
        String pair = catalog.nativePair(WebProviderCode.KRAKEN, request.instId())
                .orElseThrow(() -> new OrderRejectedException(PAIR_UNKNOWN,
                        "paire native Kraken inconnue au catalogue pour " + request.instId()));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pair", pair);
        params.put("type", request.side() == StepSide.BUY ? "buy" : "sell");
        params.put("ordertype", "limit");
        params.put("volume", request.sz().toPlainString());
        params.put("price", request.px().toPlainString());
        params.put("timeinforce", "IOC");
        params.put("oflags", "fciq");
        params.put("cl_ord_id", krakenClientId(request.clOrdId()));
        return params;
    }

    // ------------------------------------------------------------------ transport

    private JsonNode call(ApiCredential credential, String method, Map<String, String> params) {
        throttle();
        String body;
        try {
            body = KrakenSignedRequests.privatePost(clock, credential, method, params);
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException("Kraken : issue inconnue (" + e.getMessage() + ")", e);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ExchangeUnavailableException("Kraken : réponse inattendue (" + abbreviate(body) + ")");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ExchangeUnavailableException("Kraken : réponse illisible (" + abbreviate(body) + ")", e);
        }
    }

    /** Lecture : erreurs traitées comme pour un GET, retourne {@code result}. */
    private JsonNode readResult(ApiCredential credential, String method, Map<String, String> params) {
        JsonNode root = call(credential, method, params);
        checkErrors(root, false);
        return root.path("result");
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExchangeUnavailableException("Kraken : attente interrompue");
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private static void requireComplete(ApiCredential credential) {
        try {
            KrakenSignedRequests.requireComplete(credential);
        } catch (BalanceUnavailableException e) {
            throw new TradeCredentialRejectedException("Credential TRADE incomplète");
        }
    }

    // ------------------------------------------------------------------ erreurs et réponses

    private static void checkErrors(JsonNode root, boolean post) {
        JsonNode errors = root.path("error");
        if (!errors.isArray()) {
            throw new ExchangeUnavailableException("Kraken : réponse sans champ error");
        }
        if (errors.isEmpty()) {
            return;
        }
        String error = errors.path(0).asText("");
        throwFor(classify(error, post), error);
    }

    private static Kind classify(String error, boolean post) {
        if (error.startsWith("EAPI:Invalid nonce") || error.startsWith("EService:") || error.startsWith("EGeneral:Temporary lockout")
                || error.startsWith("EAPI:Rate limit") || error.startsWith("EGeneral:Internal error")) {
            return Kind.UNAVAILABLE;
        }
        if (error.startsWith("EAPI:Invalid") || error.contains("Permission denied")) {
            return Kind.CREDENTIAL;
        }
        if (!post && error.contains("Unknown order")) {
            return Kind.NOT_FOUND;
        }
        if (post && (error.startsWith("EOrder:") || error.startsWith("EGeneral:Invalid arguments"))) {
            return Kind.REJECTED;
        }
        return Kind.UNAVAILABLE;
    }

    private static void throwFor(Kind kind, String error) {
        switch (kind) {
            case CREDENTIAL -> throw new TradeCredentialRejectedException("Kraken : clé Trade refusée (" + error + ")");
            case NOT_FOUND -> throw new OrderNotFoundException("Kraken : ordre introuvable");
            case REJECTED -> throw new OrderRejectedException(error, error);
            default -> throw new ExchangeUnavailableException("Kraken : erreur " + error);
        }
    }

    /** Réponse réduite à la liste blanche {@code error} + {@code result.descr.order|close} (rien d'autre n'est conservé). */
    static String filtered(JsonNode root) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode errors = out.putArray("error");
        root.path("error").forEach(e -> errors.add(e.asText("")));
        JsonNode descr = root.path("result").path("descr");
        if (descr.isObject()) {
            ObjectNode kept = out.putObject("result").putObject("descr");
            for (String field : new String[] {"order", "close"}) {
                if (descr.hasNonNull(field)) {
                    kept.put(field, descr.path(field).asText());
                }
            }
        }
        return out.toString();
    }

    private static Map.Entry<String, JsonNode> first(JsonNode orders) {
        Iterator<Map.Entry<String, JsonNode>> it = orders.fields();
        return it.hasNext() ? it.next() : null;
    }

    /** {@code pending / open} = vivant ; {@code closed} = rempli en entier, sinon solde IOC annulé ; {@code canceled / expired} = annulé. */
    private static OrderPhase phaseOf(String status, BigDecimal vol, BigDecimal executed) {
        return switch (status) {
            case "pending", "open" -> executed.signum() > 0 ? OrderPhase.PARTIALLY_FILLED : OrderPhase.LIVE;
            case "closed" -> executed.compareTo(vol) >= 0 && vol.signum() > 0 ? OrderPhase.FILLED : OrderPhase.CANCELED;
            case "canceled", "expired" -> OrderPhase.CANCELED;
            default -> OrderPhase.UNKNOWN;
        };
    }

    private static String quoteOf(String instId) {
        return instId.substring(instId.indexOf('-') + 1);
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String text = node.path(field).asText("");
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new ExchangeUnavailableException("Kraken : champ " + field + " illisible");
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
