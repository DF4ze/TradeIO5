package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Transport Bitvavo (API v2) partagé par les lecteurs (clé « Voir ») et par le client d'ordres du package
 * {@code service.execution.exchange} (clé « Trader ») : il envoie, il ne connaît aucun ordre. Signature privée :
 * {@code Bitvavo-Access-Signature} = HMAC-SHA256 hexadécimal, clé = secret, message = {@code timestamp + MÉTHODE + chemin complet
 * (avec /v2 et la query) + corps} sans séparateur ; en-têtes {@code Bitvavo-Access-Key}, {@code -Timestamp} (ms) et
 * {@code -Window}. Pas de passphrase. L'URL de base ({@code https://api.bitvavo.com/v2}) vient du {@link WebProvider}
 * <b>déclaré par l'admin</b> : provider absent, sans URL ou désactivé => aucun appel réseau. Aucun secret n'est loggué ni mis
 * dans une exception.
 */
public final class BitvavoRequests {

    static final String HEADER_KEY = "Bitvavo-Access-Key";
    static final String HEADER_SIGNATURE = "Bitvavo-Access-Signature";
    static final String HEADER_TIMESTAMP = "Bitvavo-Access-Timestamp";
    static final String HEADER_WINDOW = "Bitvavo-Access-Window";
    /** Fenêtre d'exécution demandée (ms) : défaut documenté, plafond 60 000. */
    static final long WINDOW_MS = 10_000;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int ABBREVIATION = 200;
    private static final String HMAC = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Réponse HTTP brute : le statut est interprété par l'appelant (lecture ou ordre). */
    public record Reply(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Toute la famille 3xx d'authentification / permission est servie en HTTP 403 (clé inactive, IP, signature, droit manquant). */
        public boolean credentialRefused() {
            return status == 401 || status == 403;
        }

        /** {@code errorCode} du corps d'erreur Bitvavo, ou {@code -1}. */
        public int errorCode() {
            return errorCodeOf(body);
        }
    }

    private BitvavoRequests() {
    }

    /** {@code true} si le provider est déclaré, a une URL et n'est pas désactivé. */
    public static boolean usable(WebProvider provider) {
        return provider != null && provider.isEnabled() && provider.getApiBaseUrl() != null && !provider.getApiBaseUrl().isBlank();
    }

    /**
     * @throws BalanceUnavailableException credential absente / sans clé ni secret, provider non déclaré, sans URL ou désactivé
     */
    public static void requireComplete(ApiCredential credential) {
        if (credential == null || isBlank(credential.getApiKey()) || isBlank(credential.getSecretKey())) {
            throw new BalanceUnavailableException("Bitvavo : credential incomplète (clé API ou secret manquant)");
        }
        if (!usable(credential.getWebProvider())) {
            throw new BalanceUnavailableException("Bitvavo : provider non déclaré ou désactivé");
        }
    }

    /** GET public (sans clé). */
    public static Reply publicGet(WebProvider provider, String pathAndQuery) {
        if (!usable(provider)) {
            throw new BalanceUnavailableException("Bitvavo : provider non déclaré ou désactivé");
        }
        return exchange(provider.getApiBaseUrl(), HttpMethod.GET, pathAndQuery, null, null, 0);
    }

    /** GET privé signé. */
    public static Reply get(ApiCredential credential, String pathAndQuery) {
        return send(HttpMethod.GET, credential, pathAndQuery, null);
    }

    /**
     * Requête privée signée ; {@code body} JSON facultatif (signé tel quel : la même chaîne est envoyée).
     *
     * @throws BalanceUnavailableException credential incomplète ou appel impossible (délai, réseau) ; un statut HTTP d'erreur
     *         n'est PAS une exception ici, c'est une {@link Reply}
     */
    public static Reply send(HttpMethod method, ApiCredential credential, String pathAndQuery, String body) {
        requireComplete(credential);
        return exchange(credential.getWebProvider().getApiBaseUrl(), method, pathAndQuery, credential, body, System.currentTimeMillis());
    }

    /** Chaîne signée : {@code timestamp + méthode + chemin complet + corps}. Visible pour les tests. */
    public static String stringToSign(long timestamp, String method, String fullPath, String body) {
        return timestamp + method + fullPath + (body == null ? "" : body);
    }

    /** HMAC-SHA256 hexadécimal. Visible pour les tests. */
    public static String sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    private static Reply exchange(String baseUrl, HttpMethod method, String pathAndQuery, ApiCredential credential, String body,
                                  long timestamp) {
        try {
            WebClient.RequestBodySpec spec = WebClient.builder().baseUrl(baseUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES)).build()
                    .method(method).uri(pathAndQuery).accept(MediaType.APPLICATION_JSON);
            if (credential != null) {
                String fullPath = basePath(baseUrl) + pathAndQuery;
                String signature = sign(credential.getSecretKey().strip(), stringToSign(timestamp, method.name(), fullPath, body));
                spec.headers(h -> {
                    h.set(HEADER_KEY, credential.getApiKey().strip());
                    h.set(HEADER_SIGNATURE, signature);
                    h.set(HEADER_TIMESTAMP, String.valueOf(timestamp));
                    h.set(HEADER_WINDOW, String.valueOf(WINDOW_MS));
                });
            }
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            }
            Reply reply = spec.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                    .map(text -> new Reply(response.statusCode().value(), text))).block(TIMEOUT);
            if (reply == null) {
                throw new BalanceUnavailableException("Bitvavo : réponse vide");
            }
            return reply;
        } catch (BalanceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BalanceUnavailableException("Bitvavo : appel impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Chemin de l'URL de base sans barre finale ({@code /v2}) : il fait partie du message signé. */
    static String basePath(String baseUrl) {
        String path = URI.create(baseUrl.strip()).getPath();
        return path == null || path.equals("/") ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * Lecture : exige un 2xx et un JSON. 401/403 => {@link CredentialRejectedException}, tout autre échec =>
     * {@link BalanceUnavailableException}.
     */
    public static JsonNode requireJson(Reply reply, String what) {
        if (reply.credentialRefused()) {
            throw new CredentialRejectedException("Bitvavo : clé refusée (HTTP " + reply.status() + ", code " + reply.errorCode()
                    + ") sur " + what);
        }
        if (!reply.ok()) {
            throw new BalanceUnavailableException("Bitvavo : " + what + " en erreur HTTP " + reply.status() + " "
                    + abbreviate(reply.body()));
        }
        return readJson(reply.body(), what);
    }

    /** @throws BalanceUnavailableException corps illisible */
    public static JsonNode readJson(String body, String what) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new BalanceUnavailableException("Bitvavo : réponse " + what + " vide");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new BalanceUnavailableException("Bitvavo : réponse " + what + " illisible", e);
        }
    }

    /** {@code error} du corps d'erreur, sinon extrait du corps. */
    public static String errorOf(String body) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root != null && root.isObject() && root.path("error").isTextual() && !root.path("error").asText().isBlank()) {
                return root.path("error").asText();
            }
        } catch (JsonProcessingException e) {
            // corps non JSON : extrait brut ci-dessous
        }
        return abbreviate(body);
    }

    static int errorCodeOf(String body) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            return root != null && root.isObject() && root.path("errorCode").canConvertToInt() ? root.path("errorCode").asInt() : -1;
        } catch (JsonProcessingException e) {
            return -1;
        }
    }

    public static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ABBREVIATION ? body : body.substring(0, ABBREVIATION) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
