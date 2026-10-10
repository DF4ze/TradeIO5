package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Transport OKX <b>signé</b> partagé par {@link OkxBalanceReader}, {@link OkxTradingFeeProvider} (GET, clé Read) et par le
 * client d'ordres du package {@code service.execution.exchange} (POST, clé Trade) : signature, en-têtes, enveloppe
 * {@code {code,msg,data}} et mapping des erreurs en exceptions typées. Ce helper ne connaît aucun ordre : il signe et envoie.
 * <p>
 * {@code OK-ACCESS-SIGN = base64(HMAC-SHA256(secret, timestamp + méthode + chemin(+query) + corps))}, timestamp ISO 8601
 * UTC à la milliseconde (écart toléré par OKX : 30 s). Aucun secret n'est loggué ni mis dans une exception.
 */
public final class OkxSignedRequests {

    public static final String OK_CODE = "0";
    /** Codes OKX de rejet de la clé : 50100 à 50114 (clé, signature, passphrase, timestamp, permission, IP). */
    public static final Pattern CREDENTIAL_ERROR_CODE = Pattern.compile("501(0\\d|1[0-4])");

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OkxSignedRequests() {
    }

    /**
     * GET signé ; {@code requestPath} inclut la query string (elle fait partie du message signé).
     *
     * @return le corps de la réponse (y compris un corps d'erreur OKX sur HTTP 4xx/5xx, exploité par {@link #readData})
     * @throws BalanceUnavailableException credential incomplète, réponse vide, appel impossible
     */
    public static String signedGet(DomainClock clock, ApiCredential credential, String requestPath) {
        return signedRequest(clock, credential, HttpMethod.GET, requestPath, "");
    }

    /** POST signé : {@code jsonBody} est envoyé tel quel (c'est exactement la chaîne signée). Mêmes retours / erreurs que {@link #signedGet}. */
    public static String signedPost(DomainClock clock, ApiCredential credential, String requestPath, String jsonBody) {
        return signedRequest(clock, credential, HttpMethod.POST, requestPath, jsonBody);
    }

    private static String signedRequest(DomainClock clock, ApiCredential credential, HttpMethod method, String requestPath,
                                        String requestBody) {
        requireComplete(credential);
        String timestamp = TIMESTAMP_FORMAT.format(clock.now());
        String sign = sign(timestamp, method.name(), requestPath, requestBody, credential.getSecretKey());
        try {
            WebClient.RequestBodySpec request = WebClient.builder().baseUrl(credential.getWebProvider().getApiBaseUrl()).build()
                    .method(method)
                    .uri(requestPath)
                    .header("OK-ACCESS-KEY", credential.getApiKey())
                    .header("OK-ACCESS-SIGN", sign)
                    .header("OK-ACCESS-TIMESTAMP", timestamp)
                    .header("OK-ACCESS-PASSPHRASE", credential.getPassphrase())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            WebClient.RequestHeadersSpec<?> ready = method == HttpMethod.POST
                    ? request.contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody) : request;
            String body = ready
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

    /**
     * Parse l'enveloppe et vérifie {@code code = 0}.
     *
     * @return la racine JSON
     * @throws CredentialRejectedException clé/signature/passphrase/permission/IP rejetées (50100-50114)
     * @throws BalanceUnavailableException réponse illisible/inattendue ou autre erreur API
     */
    public static JsonNode readData(String body) {
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
        return root;
    }

    /** Package-private pour les tests. */
    public static String sign(String timestamp, String method, String path, String body, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] signature = mac.doFinal((timestamp + method + path + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new BalanceUnavailableException("OKX : signature impossible", e);
        }
    }

    public static void requireComplete(ApiCredential credential) {
        if (credential == null || isBlank(credential.getApiKey()) || isBlank(credential.getSecretKey())
                || isBlank(credential.getPassphrase()) || credential.getWebProvider() == null
                || isBlank(credential.getWebProvider().getApiBaseUrl())) {
            throw new BalanceUnavailableException("OKX : credential incomplète (clé, secret, passphrase ou URL manquant)");
        }
    }

    public static String abbreviate(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
