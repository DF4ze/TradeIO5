package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.OkxSignedRequests;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client d'ordres spot OKX (clé Trade). <b>Bean inactif par défaut</b> : il n'existe dans le contexte que si
 * {@code tradeio.execution.mode=LIVE} et {@code tradeio.execution.live-unlocked=true} ({@link LiveExecutionCondition}).
 * <ul>
 *   <li>{@code POST /api/v5/trade/order} : {@code tdMode=cash}, {@code ordType=ioc}, {@code sz} en base ({@code tgtCcy=base_ccy}
 *       explicite), {@code px} plafond, {@code clOrdId} ; jamais d'ordre au marché.</li>
 *   <li>{@code GET /api/v5/trade/order}, {@code POST /api/v5/trade/cancel-order}, {@code GET /api/v5/trade/fills}.</li>
 * </ul>
 * Signature et transport via {@link OkxSignedRequests} (mutualisés avec les lecteurs Read). Erreurs : rejet métier certain
 * ({@link OrderRejectedException}), clé refusée 501xx ({@link TradeCredentialRejectedException}), issue inconnue
 * ({@link ExchangeUnavailableException}). Jamais de log du corps signé ni des en-têtes d'authentification.
 */
@Slf4j
@Component
@Conditional(LiveExecutionCondition.class)
public class OkxSpotOrderClient implements SpotOrderPort {

    static final String ORDER_PATH = "/api/v5/trade/order";
    static final String CANCEL_PATH = "/api/v5/trade/cancel-order";
    static final String FILLS_PATH = "/api/v5/trade/fills";

    /** Code OKX « Order does not exist ». */
    private static final String ORDER_NOT_FOUND = "51603";
    /** Erreurs système OKX après lesquelles l'issue d'un POST est incertaine (service indisponible, timeout, système occupé). */
    private static final Set<String> UNCERTAIN_CODES = Set.of("50001", "50004", "50013", "50026");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;
    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public OkxSpotOrderClient(DomainClock clock) {
        this(clock, ExecutionDefaults.ORDER_CLIENT_MIN_INTERVAL);
    }

    OkxSpotOrderClient(DomainClock clock, Duration minInterval) {
        this.clock = clock;
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public OrderAck submit(ApiCredential credential, OrderRequest request) {
        requireComplete(credential);
        String body = orderBody(request);
        log.info("OKX ordre envoyé clOrdId={} {} {} sz={} px={}", request.clOrdId(), request.side(), request.instId(),
                request.sz().toPlainString(), request.px().toPlainString());
        JsonNode root = parse(post(credential, ORDER_PATH, body));
        JsonNode first = root.path("data").path(0);
        String code = root.path("code").asText("");
        String sCode = first.path("sCode").asText("");
        if (OkxSignedRequests.OK_CODE.equals(code) && OkxSignedRequests.OK_CODE.equals(sCode)) {
            return new OrderAck(first.path("clOrdId").asText(request.clOrdId()), first.path("ordId").asText(""));
        }
        String rejection = sCode.isBlank() || OkxSignedRequests.OK_CODE.equals(sCode) ? code : sCode;
        String message = first.path("sMsg").asText(root.path("msg").asText(""));
        throwFor(rejection, message, true);
        throw new IllegalStateException("inatteignable");
    }

    @Override
    public OrderState query(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        JsonNode root = parse(get(credential, ORDER_PATH + "?instId=" + instId + "&clOrdId=" + clOrdId));
        checkEnvelope(root, false);
        JsonNode data = root.path("data").path(0);
        if (data.isMissingNode() || !data.isObject()) {
            throw new OrderNotFoundException("Aucun ordre " + clOrdId + " sur " + instId);
        }
        return new OrderState(data.path("clOrdId").asText(clOrdId), data.path("ordId").asText(""), phaseOf(data.path("state").asText("")),
                decimal(data, "accFillSz", BigDecimal.ZERO), decimal(data, "avgPx", null));
    }

    @Override
    public void cancel(ApiCredential credential, String instId, String clOrdId) {
        requireComplete(credential);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", instId);
        payload.put("clOrdId", clOrdId);
        JsonNode root = parse(post(credential, CANCEL_PATH, json(payload)));
        JsonNode first = root.path("data").path(0);
        String sCode = first.path("sCode").asText("");
        if (OkxSignedRequests.OK_CODE.equals(root.path("code").asText("")) && OkxSignedRequests.OK_CODE.equals(sCode)) {
            return;
        }
        throwFor(sCode.isBlank() ? root.path("code").asText("") : sCode, first.path("sMsg").asText(root.path("msg").asText("")), false);
    }

    @Override
    public List<Fill> fills(ApiCredential credential, String instId, String ordId) {
        requireComplete(credential);
        JsonNode root = parse(get(credential, FILLS_PATH + "?instType=SPOT&instId=" + instId + "&ordId=" + ordId));
        checkEnvelope(root, false);
        List<Fill> out = new ArrayList<>();
        for (JsonNode f : root.path("data")) {
            BigDecimal rawFee = decimal(f, "fee", BigDecimal.ZERO);
            out.add(new Fill(f.path("tradeId").asText(), f.path("ordId").asText(ordId), f.path("clOrdId").asText(""),
                    f.path("instId").asText(instId), "buy".equalsIgnoreCase(f.path("side").asText()) ? StepSide.BUY : StepSide.SELL,
                    decimal(f, "fillPx", BigDecimal.ZERO), decimal(f, "fillSz", BigDecimal.ZERO), rawFee.negate(),
                    f.path("feeCcy").asText(""), Instant.ofEpochMilli(f.path("ts").asLong(clock.now().toEpochMilli()))));
        }
        return out;
    }

    // ------------------------------------------------------------------ corps et transport

    /** Corps exact d'un ordre : paramètres OKX en chaînes, ordre des clés stable. */
    static String orderBody(OrderRequest request) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", request.instId());
        payload.put("tdMode", "cash");
        payload.put("side", request.side() == StepSide.BUY ? "buy" : "sell");
        payload.put("ordType", "ioc");
        payload.put("sz", request.sz().toPlainString());
        payload.put("px", request.px().toPlainString());
        payload.put("tgtCcy", "base_ccy");
        payload.put("clOrdId", request.clOrdId());
        return json(payload);
    }

    private static String json(Map<String, String> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corps d'ordre non sérialisable", e);
        }
    }

    private String post(ApiCredential credential, String path, String body) {
        throttle();
        try {
            return OkxSignedRequests.signedPost(clock, credential, path, body);
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException("OKX : issue inconnue (" + e.getMessage() + ")", e);
        }
    }

    private String get(ApiCredential credential, String path) {
        throttle();
        try {
            return OkxSignedRequests.signedGet(clock, credential, path);
        } catch (BalanceUnavailableException e) {
            throw new ExchangeUnavailableException("OKX : lecture impossible (" + e.getMessage() + ")", e);
        }
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExchangeUnavailableException("OKX : attente interrompue");
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private static void requireComplete(ApiCredential credential) {
        try {
            OkxSignedRequests.requireComplete(credential);
        } catch (BalanceUnavailableException e) {
            throw new TradeCredentialRejectedException("Credential TRADE incomplète");
        }
    }

    // ------------------------------------------------------------------ réponses

    private static JsonNode parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ExchangeUnavailableException("OKX : réponse inattendue (" + OkxSignedRequests.abbreviate(body) + ")");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ExchangeUnavailableException("OKX : réponse illisible (" + OkxSignedRequests.abbreviate(body) + ")", e);
        }
    }

    /** Lecture : enveloppe {@code code=0} exigée, {@code 51603} => ordre introuvable. */
    private static void checkEnvelope(JsonNode root, boolean post) {
        String code = root.path("code").asText("");
        if (!OkxSignedRequests.OK_CODE.equals(code)) {
            throwFor(code, root.path("msg").asText(""), post);
        }
    }

    private static void throwFor(String code, String message, boolean post) {
        if (OkxSignedRequests.CREDENTIAL_ERROR_CODE.matcher(code).matches()) {
            throw new TradeCredentialRejectedException("OKX : clé Trade refusée code=" + code);
        }
        if (ORDER_NOT_FOUND.equals(code)) {
            throw new OrderNotFoundException("OKX : ordre introuvable");
        }
        if (UNCERTAIN_CODES.contains(code) || code.isBlank()) {
            throw new ExchangeUnavailableException("OKX : erreur système code=" + code + " msg=" + message);
        }
        if (!post) {
            throw new ExchangeUnavailableException("OKX : erreur de lecture code=" + code + " msg=" + message);
        }
        throw new OrderRejectedException(code, message);
    }

    private static OrderPhase phaseOf(String state) {
        return switch (state) {
            case "live" -> OrderPhase.LIVE;
            case "partially_filled" -> OrderPhase.PARTIALLY_FILLED;
            case "filled" -> OrderPhase.FILLED;
            case "canceled", "mmp_canceled" -> OrderPhase.CANCELED;
            default -> OrderPhase.UNKNOWN;
        };
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String text = node.path(field).asText("");
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new ExchangeUnavailableException("OKX : champ " + field + " illisible");
        }
    }
}
