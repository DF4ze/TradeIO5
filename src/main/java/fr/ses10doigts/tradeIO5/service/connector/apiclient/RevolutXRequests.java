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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Transport Revolut X partagé par les lecteurs (clé Read) et par le client d'ordres du package
 * {@code service.execution.exchange} (clé Trade) : il envoie, il ne connaît aucun ordre. Authentification : en-têtes
 * {@code X-Revx-API-Key}, {@code X-Revx-Timestamp} (ms) et {@code X-Revx-Signature} = Ed25519, en Base64, du message
 * {@code timestamp + MÉTHODE + chemin (depuis /api) + requête (sans ?) + corps JSON minifié}. La clé privée Ed25519 (PEM PKCS#8)
 * est le champ {@code secretKey} de la credential ; la clé publique est enregistrée côté Revolut. L'URL de base vient du
 * {@link WebProvider} <b>déclaré par l'admin</b> ({@code https://revx.revolut.com/api/1.0} ou {@code …/api}) : toutes les routes
 * ci-dessous portent leur version ({@code /1.0/…}, {@code /2.0/…}), le suffixe de version de l'URL de base est retiré. Provider
 * absent, sans URL ou désactivé => aucun appel réseau. Aucun secret n'est loggué ni mis dans une exception.
 */
public final class RevolutXRequests {

    static final String HEADER_KEY = "X-Revx-API-Key";
    static final String HEADER_TIMESTAMP = "X-Revx-Timestamp";
    static final String HEADER_SIGNATURE = "X-Revx-Signature";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int ABBREVIATION = 200;
    private static final Pattern VERSION_SUFFIX = Pattern.compile("/[0-9]+\\.[0-9]+/?$");
    private static final Pattern PEM_MARKER = Pattern.compile("-----[A-Z ]+-----|\\s");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Réponse HTTP brute : le statut est interprété par l'appelant (lecture ou ordre). */
    public record Reply(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean credentialRefused() {
            return status == 401 || status == 403;
        }
    }

    /** Ordres actifs (lecture signée) : sert aussi de sonde de validité d'une clé TRADE. */
    public static final String ACTIVE_ORDERS_PATH = "/1.0/orders/active";

    private RevolutXRequests() {
    }

    /** {@code true} si le provider est déclaré, a une URL et n'est pas désactivé. */
    public static boolean usable(WebProvider provider) {
        return provider != null && provider.isEnabled() && provider.getApiBaseUrl() != null && !provider.getApiBaseUrl().isBlank();
    }

    /** URL racine ({@code https://revx.revolut.com/api}) : base déclarée sans slash final ni suffixe de version. */
    static String root(WebProvider provider) {
        String base = provider.getApiBaseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return VERSION_SUFFIX.matcher(base).replaceFirst("");
    }

    /**
     * @throws BalanceUnavailableException credential absente / sans clé ni clé privée, provider non déclaré, sans URL ou désactivé
     */
    public static void requireComplete(ApiCredential credential) {
        if (credential == null || credential.getApiKey() == null || credential.getApiKey().isBlank()
                || credential.getSecretKey() == null || credential.getSecretKey().isBlank()) {
            throw new BalanceUnavailableException("Revolut X : credential incomplète (clé API ou clé privée Ed25519 manquante)");
        }
        if (!usable(credential.getWebProvider())) {
            throw new BalanceUnavailableException("Revolut X : provider non déclaré ou désactivé");
        }
    }

    /** GET public (sans clé). {@code path} porte sa version ({@code /1.0/public/…}). */
    public static Reply publicGet(WebProvider provider, String path, String query) {
        if (!usable(provider)) {
            throw new BalanceUnavailableException("Revolut X : provider non déclaré ou désactivé");
        }
        return exchange(root(provider), HttpMethod.GET, path, query, null, null, null);
    }

    /** Racine de l'API publique Revolut X (candles : aucune clé, aucun provider déclaré requis). */
    public static final String PUBLIC_ROOT = "https://revx.revolut.com/api";

    /** GET public sur une racine explicite (données de marché, sans {@code WebProvider}). */
    public static Reply publicGet(String root, String path, String query) {
        return exchange(root, HttpMethod.GET, path, query, null, null, null);
    }

    /** Tentatives max d'une lecture limitée en débit (429) et pause de base entre deux tentatives (x numéro de tentative). */
    static final int RATE_LIMIT_ATTEMPTS = 4;
    static final long RATE_LIMIT_PAUSE_MILLIS = 1000;

    /**
     * GET privé signé, avec nouvelle tentative après un 429 (limite de débit Revolut X : {@code Retry-After} n'est pas exposé par
     * {@link Reply}, pause croissante). Réservé aux lectures en rafale (historique) ; les autres appels échouent tout de suite.
     */
    public static Reply getWithRetry(ApiCredential credential, String path, String query) {
        return retryOnRateLimit(() -> get(credential, path, query), RATE_LIMIT_ATTEMPTS, RATE_LIMIT_PAUSE_MILLIS);
    }

    static Reply retryOnRateLimit(java.util.function.Supplier<Reply> call, int attempts, long pauseMillis) {
        Reply reply = call.get();
        for (int attempt = 1; reply.status() == 429 && attempt < attempts; attempt++) {
            try {
                Thread.sleep(pauseMillis * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return reply;
            }
            reply = call.get();
        }
        return reply;
    }

    /** GET public avec les mêmes tentatives après 429 que {@link #getWithRetry} (carnet : Fee Test, plan d'ordres). */
    public static Reply publicGetWithRetry(WebProvider provider, String path, String query) {
        return retryOnRateLimit(() -> publicGet(provider, path, query), RATE_LIMIT_ATTEMPTS, RATE_LIMIT_PAUSE_MILLIS);
    }

    /** GET privé signé. */
    public static Reply get(ApiCredential credential, String path, String query) {
        return send(HttpMethod.GET, credential, path, query, null);
    }

    /**
     * Requête privée signée ; {@code body} JSON minifié facultatif (le texte signé est le texte envoyé).
     *
     * @throws BalanceUnavailableException credential incomplète ou appel impossible (délai, réseau) ;
     *         {@link CredentialRejectedException} si la clé privée est illisible ; un statut HTTP d'erreur n'est PAS une
     *         exception ici, c'est une {@link Reply}
     */
    public static Reply send(HttpMethod method, ApiCredential credential, String path, String query, String body) {
        requireComplete(credential);
        return exchange(root(credential.getWebProvider()), method, path, query, body, credential.getApiKey().strip(),
                privateKey(credential.getSecretKey()));
    }

    /** Clé privée Ed25519 depuis un PEM PKCS#8 (« BEGIN PRIVATE KEY ») ou le Base64 nu de sa partie DER. */
    static PrivateKey privateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(PEM_MARKER.matcher(pem).replaceAll(""));
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CredentialRejectedException("Revolut X : clé privée Ed25519 illisible (PEM PKCS#8 attendu)");
        }
    }

    /** Signature Base64 du message {@code timestamp + méthode + chemin + requête + corps} (concaténés sans séparateur). */
    static String sign(PrivateKey key, String timestamp, String method, String signedPath, String query, String body) {
        String message = timestamp + method + signedPath + (query == null ? "" : query) + (body == null ? "" : body);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new CredentialRejectedException("Revolut X : signature Ed25519 impossible");
        }
    }

    private static Reply exchange(String root, HttpMethod method, String path, String query, String body, String apiKey,
                                  PrivateKey key) {
        String cleanQuery = query == null || query.isBlank() ? null : query;
        try {
            URI uri = URI.create(root + path + (cleanQuery == null ? "" : "?" + cleanQuery));
            WebClient.RequestBodySpec spec = WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES))
                    .build().method(method).uri(uri).accept(MediaType.APPLICATION_JSON);
            if (key != null) {
                String timestamp = String.valueOf(System.currentTimeMillis());
                String signature = sign(key, timestamp, method.name(), URI.create(root).getPath() + path, cleanQuery, body);
                spec.header(HEADER_KEY, apiKey).header(HEADER_TIMESTAMP, timestamp).header(HEADER_SIGNATURE, signature);
            }
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            }
            Reply reply = spec.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                    .map(text -> new Reply(response.statusCode().value(), text))).block(TIMEOUT);
            if (reply == null) {
                throw new BalanceUnavailableException("Revolut X : réponse vide");
            }
            return reply;
        } catch (BalanceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BalanceUnavailableException("Revolut X : appel impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /**
     * Lecture : exige un 2xx et un JSON. 401/403 => {@link CredentialRejectedException}, tout autre échec =>
     * {@link BalanceUnavailableException}.
     */
    public static JsonNode requireJson(Reply reply, String what) {
        if (reply.credentialRefused()) {
            throw new CredentialRejectedException("Revolut X : clé refusée (HTTP " + reply.status() + ") sur " + what);
        }
        if (!reply.ok()) {
            throw new BalanceUnavailableException("Revolut X : " + what + " en erreur HTTP " + reply.status() + " "
                    + abbreviate(errorOf(reply.body())));
        }
        return readJson(reply.body(), what);
    }

    /** @throws BalanceUnavailableException corps illisible */
    public static JsonNode readJson(String body, String what) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new BalanceUnavailableException("Revolut X : réponse " + what + " vide");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new BalanceUnavailableException("Revolut X : réponse " + what + " illisible", e);
        }
    }

    /** Message d'erreur du corps ({@code message}), sinon extrait du corps. */
    public static String errorOf(String body) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root != null && root.isObject() && root.path("message").isTextual() && !root.path("message").asText().isBlank()) {
                return root.path("message").asText();
            }
        } catch (JsonProcessingException e) {
            // corps non JSON : extrait brut ci-dessous
        }
        return abbreviate(body);
    }

    /** Tableau d'une réponse : racine tableau, ou champ {@code data} (objet unique ramené à un tableau d'un élément). */
    public static JsonNode dataOf(JsonNode root) {
        JsonNode data = root.isObject() && root.has("data") ? root.path("data") : root;
        if (data.isObject()) {
            return MAPPER.createArrayNode().add(data);
        }
        return data;
    }

    public static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ABBREVIATION ? body : body.substring(0, ABBREVIATION) + "…";
    }
}
