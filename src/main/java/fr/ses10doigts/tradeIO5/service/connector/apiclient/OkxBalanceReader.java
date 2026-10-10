package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceCacheManager;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Soldes OKX en lecture seule : compte <b>Trading</b>, {@code GET /api/v5/account/balance}, solde disponible
 * ({@code availBal}) par devise. Un seul appel par lecture (limite OKX : 10 requêtes / 2 s), mis en cache.
 * Clé API attendue : permission « Read » uniquement, IP allowlistée.
 * <p>
 * Authentification : {@code OK-ACCESS-SIGN = base64(HMAC-SHA256(secret, timestamp + "GET" + chemin + corps))}, timestamp
 * ISO 8601 UTC à la milliseconde (écart toléré par OKX : 30 s). Aucun secret n'est loggué ni mis dans une exception.
 */
@Component
public class OkxBalanceReader implements ReadOnlyBalanceReader {

    private static final Logger logger = LoggerFactory.getLogger(OkxBalanceReader.class);

    static final String BALANCE_PATH = "/api/v5/account/balance";

    private static final String OK_CODE = "0";
    /** Codes OKX de rejet de la clé : 50100 à 50114 (clé, signature, passphrase, timestamp, permission, IP). */
    private static final java.util.regex.Pattern CREDENTIAL_ERROR_CODE = java.util.regex.Pattern.compile("501(0\\d|1[0-4])");
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;
    private final BalanceCacheManager balanceCacheManager;

    public OkxBalanceReader(DomainClock clock) {
        this.clock = clock;
        this.balanceCacheManager = new BalanceCacheManager(clock);
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public Map<String, BigDecimal> getAvailableBalances(ApiCredential credential) {
        return balanceCacheManager.getBalances(this::fetchAvailableBalances, credential);
    }

    @Override
    public Map<String, BigDecimal> fetchAvailableBalances(ApiCredential credential) {
        requireComplete(credential);
        String body = get(credential);
        Map<String, BigDecimal> balances = parseBalances(body);
        logger.info("📦 [OKX] {} soldes disponibles lus (compte Trading)", balances.size());
        return balances;
    }

    private String get(ApiCredential credential) {
        String timestamp = TIMESTAMP_FORMAT.format(clock.now());
        String sign = sign(timestamp, "GET", BALANCE_PATH, "", credential.getSecretKey());
        try {
            String body = WebClient.builder().baseUrl(credential.getWebProvider().getApiBaseUrl()).build()
                    .get()
                    .uri(BALANCE_PATH)
                    .header("OK-ACCESS-KEY", credential.getApiKey())
                    .header("OK-ACCESS-SIGN", sign)
                    .header("OK-ACCESS-TIMESTAMP", timestamp)
                    .header("OK-ACCESS-PASSPHRASE", credential.getPassphrase())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    // Le corps d'erreur OKX (code/msg) est exploité par parseBalances même sur HTTP 4xx/5xx.
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(text -> response.statusCode().is2xxSuccessful() || text.trim().startsWith("{")
                                    ? text
                                    : "HTTP " + response.statusCode().value()))
                    .block(TIMEOUT);
            if (body == null || body.isBlank()) {
                throw new BalanceUnavailableException("OKX : réponse vide");
            }
            return body;
        } catch (BalanceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BalanceUnavailableException("OKX : appel impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Package-private pour les tests : parsing de la réponse {@code /account/balance}. */
    static Map<String, BigDecimal> parseBalances(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new BalanceUnavailableException("OKX : réponse illisible (" + abbreviate(body) + ")", e);
        }
        if (root == null || !root.isObject()) {
            throw new BalanceUnavailableException("OKX : réponse inattendue (" + abbreviate(body) + ")");
        }
        String code = root.path("code").asText("");
        if (!OK_CODE.equals(code)) {
            String message = "OKX : erreur API code=" + code + " msg=" + root.path("msg").asText("");
            throw CREDENTIAL_ERROR_CODE.matcher(code).matches()
                    ? new CredentialRejectedException(message) : new BalanceUnavailableException(message);
        }
        JsonNode details = root.path("data").path(0).path("details");
        if (!details.isArray()) {
            throw new BalanceUnavailableException("OKX : champ data[0].details absent");
        }

        Map<String, BigDecimal> balances = new HashMap<>();
        for (JsonNode detail : details) {
            String ccy = detail.path("ccy").asText("");
            if (ccy.isBlank()) {
                throw new BalanceUnavailableException("OKX : devise absente dans details");
            }
            BigDecimal available = parseAmount(detail.path("availBal").asText(""), ccy);
            if (available.signum() > 0) {
                balances.put(ccy.toUpperCase(), available);
            }
        }
        return balances;
    }

    private static BigDecimal parseAmount(String text, String ccy) {
        if (text.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("OKX : solde illisible pour " + ccy, e);
        }
    }

    /** Package-private pour les tests. */
    static String sign(String timestamp, String method, String path, String body, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] signature = mac.doFinal((timestamp + method + path + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new BalanceUnavailableException("OKX : signature impossible", e);
        }
    }

    private static void requireComplete(ApiCredential credential) {
        if (credential == null || isBlank(credential.getApiKey()) || isBlank(credential.getSecretKey())
                || isBlank(credential.getPassphrase()) || credential.getWebProvider() == null
                || isBlank(credential.getWebProvider().getApiBaseUrl())) {
            throw new BalanceUnavailableException("OKX : credential incomplète (clé, secret, passphrase ou URL manquant)");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String abbreviate(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
