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

import java.time.Duration;

/**
 * Transport One Trading partagé par les lecteurs (clé Read) et par le client d'ordres du package
 * {@code service.execution.exchange} (clé Trade) : il envoie, il ne connaît aucun ordre. Authentification : en-tête
 * {@code Authorization: Bearer <clé API>} (aucun secret, aucune signature, aucune passphrase). L'URL de base
 * ({@code https://api.onetrading.com/fast/v1}) vient du {@link WebProvider} <b>déclaré par l'admin</b> : provider absent,
 * sans URL ou désactivé => aucun appel réseau. Aucun secret n'est loggué ni mis dans une exception.
 */
public final class OneTradingRequests {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int ABBREVIATION = 200;
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

    private OneTradingRequests() {
    }

    /** {@code BTC-USDC} -> {@code BTC_USDC} (code d'instrument One Trading). */
    public static String nativeCode(String instId) {
        return instId.toUpperCase().replace('-', '_');
    }

    /** {@code true} si le provider est déclaré, a une URL et n'est pas désactivé. */
    public static boolean usable(WebProvider provider) {
        return provider != null && provider.isEnabled() && provider.getApiBaseUrl() != null && !provider.getApiBaseUrl().isBlank();
    }

    /**
     * @throws BalanceUnavailableException credential absente / sans clé, provider non déclaré, sans URL ou désactivé
     */
    public static void requireComplete(ApiCredential credential) {
        if (credential == null || credential.getApiKey() == null || credential.getApiKey().isBlank()) {
            throw new BalanceUnavailableException("One Trading : credential incomplète (clé API manquante)");
        }
        if (!usable(credential.getWebProvider())) {
            throw new BalanceUnavailableException("One Trading : provider non déclaré ou désactivé");
        }
    }

    /** GET public (sans clé). */
    public static Reply publicGet(WebProvider provider, String pathAndQuery) {
        if (!usable(provider)) {
            throw new BalanceUnavailableException("One Trading : provider non déclaré ou désactivé");
        }
        return exchange(provider.getApiBaseUrl(), HttpMethod.GET, pathAndQuery, null, null);
    }

    /** GET privé ({@code Authorization: Bearer}). */
    public static Reply get(ApiCredential credential, String pathAndQuery) {
        return send(HttpMethod.GET, credential, pathAndQuery, null);
    }

    /**
     * Requête privée ; {@code body} JSON facultatif.
     *
     * @throws BalanceUnavailableException credential incomplète ou appel impossible (délai, réseau) ; un statut HTTP d'erreur
     *         n'est PAS une exception ici, c'est une {@link Reply}
     */
    public static Reply send(HttpMethod method, ApiCredential credential, String pathAndQuery, String body) {
        requireComplete(credential);
        return exchange(credential.getWebProvider().getApiBaseUrl(), method, pathAndQuery, credential.getApiKey().strip(), body);
    }

    private static Reply exchange(String baseUrl, HttpMethod method, String pathAndQuery, String bearer, String body) {
        try {
            WebClient.RequestBodySpec spec = WebClient.builder().baseUrl(baseUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES)).build()
                    .method(method).uri(pathAndQuery).accept(MediaType.APPLICATION_JSON);
            if (bearer != null) {
                spec.headers(h -> h.setBearerAuth(bearer));
            }
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            }
            Reply reply = spec.exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                    .map(text -> new Reply(response.statusCode().value(), text))).block(TIMEOUT);
            if (reply == null) {
                throw new BalanceUnavailableException("One Trading : réponse vide");
            }
            return reply;
        } catch (BalanceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BalanceUnavailableException("One Trading : appel impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /**
     * Lecture : exige un 2xx et un JSON. 401/403 => {@link CredentialRejectedException}, tout autre échec =>
     * {@link BalanceUnavailableException}.
     */
    public static JsonNode requireJson(Reply reply, String what) {
        if (reply.credentialRefused()) {
            throw new CredentialRejectedException("One Trading : clé refusée (HTTP " + reply.status() + ") sur " + what);
        }
        if (!reply.ok()) {
            throw new BalanceUnavailableException("One Trading : " + what + " en erreur HTTP " + reply.status() + " "
                    + abbreviate(reply.body()));
        }
        return readJson(reply.body(), what);
    }

    /** @throws BalanceUnavailableException corps illisible */
    public static JsonNode readJson(String body, String what) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new BalanceUnavailableException("One Trading : réponse " + what + " vide");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new BalanceUnavailableException("One Trading : réponse " + what + " illisible", e);
        }
    }

    /** Message d'erreur du corps ({@code error}, sinon {@code message}), sinon extrait du corps. */
    public static String errorOf(String body) {
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            if (root != null && root.isObject()) {
                for (String field : new String[]{"error", "message"}) {
                    if (root.path(field).isTextual() && !root.path(field).asText().isBlank()) {
                        return root.path(field).asText();
                    }
                }
            }
        } catch (JsonProcessingException e) {
            // corps non JSON : extrait brut ci-dessous
        }
        return abbreviate(body);
    }

    public static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ABBREVIATION ? body : body.substring(0, ABBREVIATION) + "…";
    }
}
