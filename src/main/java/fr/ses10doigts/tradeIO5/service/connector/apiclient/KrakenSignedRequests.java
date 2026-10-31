package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Transport Kraken <b>signé</b> partagé par {@link KrakenApiClient} (lecture, clé Read) et par le client d'ordres du package
 * {@code service.execution.exchange} (clé Trade), comme {@link OkxSignedRequests} côté OKX : il signe et envoie, il ne
 * connaît aucun ordre.
 * <p>
 * {@code API-Sign = base64(HMAC-SHA512(base64decode(secret), chemin + SHA256(nonce + corps encodé)))}. Le <b>nonce</b> doit
 * être strictement croissant pour une même clé API : un compteur par empreinte de clé (jamais la clé en clair comme clé de
 * map) vaut {@code max(maintenant en ms, dernier + 1)}, thread-safe, partagé par toutes les instances (clé Read et clé Trade
 * identiques comprises). Aucun secret n'est loggué ni mis dans une exception.
 */
public final class KrakenSignedRequests {

    private static final String API_VERSION = "0";
    private static final String HMAC_SHA512 = "HmacSHA512";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, AtomicLong> LAST_NONCE = new ConcurrentHashMap<>();
    /** Un verrou par clé API : nonce et envoi sont sérialisés, sinon Kraken reçoit des nonces dans le désordre (EAPI:Invalid nonce). */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private KrakenSignedRequests() {
    }

    /**
     * POST privé signé ({@code /0/private/<method>}), corps {@code application/x-www-form-urlencoded}.
     *
     * @return le corps de la réponse (y compris un corps d'erreur Kraken sur HTTP 4xx/5xx), jamais vide
     * @throws BalanceUnavailableException credential incomplète, signature impossible, réponse vide, appel impossible
     */
    public static String privatePost(DomainClock clock, ApiCredential credential, String method, Map<String, String> params) {
        requireComplete(credential);
        synchronized (LOCKS.computeIfAbsent(fingerprint(credential.getApiKey()), k -> new Object())) {
            return send(clock, credential, method, params);
        }
    }

    private static String send(DomainClock clock, ApiCredential credential, String method, Map<String, String> params) {
        String path = "/" + API_VERSION + "/private/" + method;
        long nonce = nextNonce(clock, credential.getApiKey());
        Map<String, String> postData = new LinkedHashMap<>();
        postData.put("nonce", String.valueOf(nonce));
        postData.putAll(params);
        String encoded = encode(postData);
        String signature = sign(path, nonce, encoded, credential.getSecretKey());
        try {
            String body = WebClient.builder().baseUrl(credential.getWebProvider().getApiBaseUrl()).build()
                    .post()
                    .uri(path)
                    .header("API-Key", credential.getApiKey())
                    .header("API-Sign", signature)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(encoded)
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(text -> response.statusCode().is2xxSuccessful() || text.trim().startsWith("{")
                                    ? text
                                    : "HTTP " + response.statusCode().value()))
                    .block(TIMEOUT);
            if (body == null || body.isBlank()) {
                throw new BalanceUnavailableException("Kraken : réponse vide");
            }
            return body;
        } catch (BalanceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BalanceUnavailableException("Kraken : appel impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Nonce strictement croissant pour cette clé API : {@code max(maintenant en ms, dernier + 1)}. */
    public static long nextNonce(DomainClock clock, String apiKey) {
        long now = clock.now().toEpochMilli();
        return LAST_NONCE.computeIfAbsent(fingerprint(apiKey), k -> new AtomicLong()).updateAndGet(last -> Math.max(now, last + 1));
    }

    /** Signature {@code API-Sign} (vecteur de test publié par Kraken couvert par un test). */
    public static String sign(String path, long nonce, String encodedPostData, String secretBase64) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((nonce + encodedPostData).getBytes(StandardCharsets.UTF_8));
            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            byte[] message = ByteBuffer.allocate(pathBytes.length + hash.length).put(pathBytes).put(hash).array();
            Mac mac = Mac.getInstance(HMAC_SHA512);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secretBase64), HMAC_SHA512));
            return Base64.getEncoder().encodeToString(mac.doFinal(message));
        } catch (Exception e) {
            throw new BalanceUnavailableException("Kraken : signature impossible (" + e.getClass().getSimpleName() + ")");
        }
    }

    public static void requireComplete(ApiCredential credential) {
        if (credential == null || isBlank(credential.getApiKey()) || isBlank(credential.getSecretKey())
                || credential.getWebProvider() == null || isBlank(credential.getWebProvider().getApiBaseUrl())) {
            throw new BalanceUnavailableException("Kraken : credential incomplète (clé, secret ou URL manquant)");
        }
    }

    private static String encode(Map<String, String> data) {
        return data.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String fingerprint(String apiKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
