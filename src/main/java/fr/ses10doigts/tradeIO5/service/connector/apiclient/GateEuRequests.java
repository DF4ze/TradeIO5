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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Transport Gate EU (API v4 spot, hôte {@code https://api.gateeu.com/api/v4}, distinct de Gate.io global : comptes et clés
 * non partagés) partagé par les lecteurs (clé lecture) et par le client d'ordres du package
 * {@code service.execution.exchange} (clé Trade) : il envoie, il ne connaît aucun ordre. Signature privée : en-têtes
 * {@code KEY}, {@code Timestamp} (secondes) et {@code SIGN} = HMAC-SHA512 hexadécimal (clé = secret) de
 * {@code MÉTHODE \n chemin complet (avec /api/v4, sans query) \n query \n hex(SHA512(corps)) \n timestamp}. Pas de passphrase.
 * L'URL de base vient du {@link WebProvider} <b>déclaré par l'admin</b> : provider absent, sans URL ou désactivé => aucun
 * appel réseau. Aucun secret n'est loggué ni mis dans une exception.
 */
public final class GateEuRequests {

    static final String HEADER_KEY = "KEY";
    static final String HEADER_TIMESTAMP = "Timestamp";
    static final String HEADER_SIGN = "SIGN";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int ABBREVIATION = 200;
    private static final String HMAC = "HmacSHA512";
    private static final String SHA512 = "SHA-512";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Réponse HTTP brute : le statut est interprété par l'appelant (lecture ou ordre). */
    public record Reply(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean credentialRefused() {
            return status == 401 || status == 403;
        }

        /** {@code label} du corps d'erreur Gate ({@code {"label":"INVALID_PARAM_VALUE","message":"..."}}), ou vide. */
        public String label() {
            return fieldOf(body, "label");
        }
    }

    private GateEuRequests() {
    }

    /** {@code BTC-EUR} -> {@code BTC_EUR} (paire Gate). */
    public static String nativePair(String instId) {
        return instId.toUpperCase().replace('-', '_');
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
            throw new BalanceUnavailableException("Gate EU : credential incomplète (clé API ou secret manquant)");
        }
        if (!usable(credential.getWebProvider())) {
            throw new BalanceUnavailableException("Gate EU : provider non déclaré ou désactivé");
        }
    }

    /** GET public (sans clé). */
    public static Reply publicGet(WebProvider provider, String pathAndQuery) {
        if (!usable(provider)) {
            throw new BalanceUnavailableException("Gate EU : provider non déclaré ou désactivé");
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
        return exchange(credential.getWebProvider().getApiBaseUrl(), method, pathAndQuery, credential, body,
                System.currentTimeMillis() / 1000);
    }

    /**
     * Chaîne signée : {@code méthode, chemin complet, query, SHA512 hex du corps, timestamp} séparés par un saut de ligne.
     * Visible pour les tests.
     */
    static String stringToSign(String method, String fullPath, String query, String body, long timestamp) {
        return method + "\n" + fullPath + "\n" + query + "\n" + sha512Hex(body == null ? "" : body) + "\n" + timestamp;
    }

    /** HMAC-SHA512 hexadécimal. Visible pour les tests. */
    static String sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA512 indisponible", e);
        }
    }

    static String sha512Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(SHA512).digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 indisponible", e);
        }
    }

    private static Reply exchange(String baseUrl, HttpMethod method, String pathAndQuery, ApiCredential credential, String body,
                                  long timestamp) {
        try {
            WebClient.RequestBodySpec spec = WebClient.builder().baseUrl(baseUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES)).build()
                    .method(method).uri(pathAndQuery).accept(MediaType.APPLICATION_JSON);
            if (credential != null) {
                int cut = pathAndQuery.indexOf('?');
                String path = cut < 0 ? pathAndQuery : pathAndQuery.substring(0, cut);
                String query = cut < 0 ? "" : pathAndQuery.substring(cut + 1);
                String signature = sign(credential.getSecretKey().strip(),
                        stringToSign(method.name(), basePath(baseUrl) + path, query, body, timestamp));
                spec.headers(h -> {
                    h.set(HEADER_KEY, credential.getApiKey().strip());
                    h.set(HEADER_TIMESTAMP, String.valueOf(timestamp));
                    h.set(HEADER_SIGN, signature);
                });
            }
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            }
            Reply reply = spec.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                    .map(text -> new Reply(response.statusCode().value(), text))).block(TIMEOUT);
            if (reply == null) {
                throw new BalanceUnavailableException("Gate EU : réponse vide");
            }
            return reply;
        } catch (BalanceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BalanceUnavailableException("Gate EU : appel impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Chemin de l'URL de base sans barre finale ({@code /api/v4}) : il fait partie du message signé. */
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
            throw new CredentialRejectedException("Gate EU : clé refusée (HTTP " + reply.status() + ", " + reply.label() + ") sur " + what);
        }
        if (!reply.ok()) {
            throw new BalanceUnavailableException("Gate EU : " + what + " en erreur HTTP " + reply.status() + " "
                    + abbreviate(reply.body()));
        }
        return readJson(reply.body(), what);
    }

    /** @throws BalanceUnavailableException corps illisible */
    public static JsonNode readJson(String body, String what) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new BalanceUnavailableException("Gate EU : réponse " + what + " vide");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new BalanceUnavailableException("Gate EU : réponse " + what + " illisible", e);
        }
    }

    /** {@code message} du corps d'erreur ({@code label} si absent), sinon extrait du corps. */
    public static String errorOf(String body) {
        String message = fieldOf(body, "message");
        if (!message.isBlank()) {
            return message;
        }
        String label = fieldOf(body, "label");
        return label.isBlank() ? abbreviate(body) : label;
    }

    private static String fieldOf(String body, String field) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root != null && root.isObject() && root.path(field).isTextual()) {
                return root.path(field).asText();
            }
        } catch (JsonProcessingException e) {
            // corps non JSON : champ vide
        }
        return "";
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
