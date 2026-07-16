package fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Client SoSoValue (docs/etude-sourcing-etf-flow-alternative-farside.md) : remplace
 * {@code FarsideEtfFlowClient} (scraping HTML non versionné, retiré de la config Spring le
 * 2026-07-16) par une API REST officielle et documentée
 * (<a href="https://sosovalue.gitbook.io/soso-value-api-doc">sosovalue.gitbook.io</a>). Auth par
 * header {@code x-soso-api-key} (pas de query param, contrairement à Coinalyze/Twelve Data),
 * endpoint {@code GET /etfs/summary-history?symbol=BTC|ETH&country_code=US&limit=1}.
 * <p>
 * <b>Enveloppe de réponse corrigée après un premier appel réel</b> (2026-07-16, clé Clem) : la
 * page de doc de cet endpoint spécifique montre un exemple qui est directement un tableau JSON,
 * mais toutes les réponses SoSoValue sont en réalité enveloppées
 * ({@code {"code":0,"message":"success","data":[...]}}, cf. page "Response Format" générale de la
 * doc, absente de la page endpoint-spécifique). Premier symptôme observé en prod :
 * {@code parsing failed at step 'body shape': expected non-empty array, got OBJECT}. Voir
 * {@link #parse} pour le déballage de {@code data}.
 * <p>
 * <b>Choix délibéré : total agrégé seulement, jamais de détail par émetteur.</b> Contrairement à
 * Farside (une page = total + détail par émetteur en un seul scrape), SoSoValue sépare ça en
 * endpoints distincts : reconstruire {@code byIssuer} demanderait N+1 appels par asset (liste des
 * tickers via {@code /etfs} + un {@code /etfs/{ticker}/market-snapshot} par ticker, ~10-12
 * tickers), pour un gain non exploité aujourd'hui — aucune {@code Strategy} ne consomme
 * {@code byIssuer} (cf. étude "indicateurs-macro-externes" §5 : ETF_FLOW ne doit "jamais" être un
 * signal directionnel non supervisé). Décision validée avec Clem le 2026-07-16 :
 * {@link EtfFlowResponse#getByIssuer()} reste toujours une map vide (jamais {@code null}, pour
 * rester compatible avec {@code EtfFlowIndicator.compute}) pour ce client. À réévaluer si un
 * besoin par émetteur apparaît un jour (impact : ~22-26 appels par cycle au lieu de 2, cf. étude).
 * <p>
 * <b>Unité différente de Farside — point d'attention pour le futur {@code CONFIDENCE_MODULATOR}.</b>
 * Farside exprimait les flux en millions USD ("US$m", {@code 54.8} = 54,8 M$). SoSoValue renvoie
 * {@code total_net_inflow} en USD brut ({@code -55066297.0} = -55,07 M$). Ce client ne fait
 * <b>aucune conversion</b> : {@link EtfFlowResponse#getTotal()} expose la valeur brute telle que
 * renvoyée par l'API. Tout seuil futur sur ETF_FLOW devra être calibré en USD brut, pas en
 * "millions" comme l'aurait suggéré l'historique Farside.
 * <p>
 * Mêmes garde-fous que les autres clients externes du projet ({@code CoinalyzeClient},
 * {@code FarsideEtfFlowClient}) : jamais d'exception qui remonte, toujours un
 * {@link EtfFlowResponse#invalid()} propre en cas de panne réseau/parsing, avec un log warn
 * distinct par étape d'échec (même objectif que Farside : détecter vite un changement de contrat
 * côté SoSoValue plutôt que de le noyer en silence).
 * <p>
 * <b>Plus {@code @Component} depuis le 2026-07-16</b> (docs/etude-cache-etf-flow-historisation.md) :
 * enveloppé dans {@link CachingEtfFlowClient}, seul bean {@link EtfFlowProvider} du contexte
 * désormais (câblage dans {@code EtfFlowCachingConfig}) — même traitement que
 * {@code FarsideEtfFlowClient} en son temps, pour éviter toute ambiguïté d'injection dans
 * {@code EtfFlowIndicator}. Toujours instanciable directement ({@code new SosoValueEtfFlowClient()}),
 * ses tests existants ne changent pas.
 */
public class SosoValueEtfFlowClient extends AbstractExternalIndicator implements EtfFlowProvider {

    private static final Logger logger = LoggerFactory.getLogger(SosoValueEtfFlowClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_KEY_HEADER = "x-soso-api-key";
    // Seul le marché US est couvert aujourd'hui (BTC/ETH spot ETFs US) — cf. EtfFlowAsset,
    // limité à BTC/ETH. SoSoValue supporte aussi "HK" (Hong Kong) mais hors périmètre actuel.
    private static final String COUNTRY_CODE = "US";

    @Override
    public EtfFlowResponse fetch(ApiCredentialDTO credential, EtfFlowAsset asset) {
        try {
            String body = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/etfs/summary-history")
                            .queryParam("symbol", asset.name())
                            .queryParam("country_code", COUNTRY_CODE)
                            .queryParam("limit", 1)
                            .build())
                    .header(API_KEY_HEADER, credential.apiKey())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            return parse(body, asset);
        } catch (ExternalApiException e) {
            logger.warn("SoSoValue ({}) unavailable: {}", asset, e.getMessage());
            return EtfFlowResponse.invalid();
        } catch (Exception e) {
            logger.error("SoSoValue ({}) unexpected error", asset, e);
            return EtfFlowResponse.invalid();
        }
    }

    /**
     * Isolé de l'appel réseau pour être testable en unitaire (patron
     * {@code FarsideEtfFlowClient.parse}). {@code summary-history} trie déjà en ordre
     * chronologique inverse (le plus récent en premier, confirmé par la doc API : "Data is sorted
     * in reverse chronological order") : le premier élément du tableau est directement la dernière
     * ligne publiée — pas besoin de la règle Farside "exclure la ligne du jour si tout est '-'"
     * (l'API ne publie une ligne qu'une fois finale, pas de ligne "en cours" exposée).
     */
    static EtfFlowResponse parse(String body, EtfFlowAsset asset) {
        JsonNode root = readTree(body);
        if (root == null) {
            logger.warn("SoSoValue ({}) parsing failed at step 'fetch': empty/invalid JSON body", asset);
            return EtfFlowResponse.invalid();
        }

        // Enveloppe commune à toute l'API SoSoValue ({"code":0,"message":"success","data":[...]})
        // documentée sur la page "Response Format" (générale), absente de l'exemple de réponse de
        // la page "2.1 ETF Summary History" (endpoint-spécifique) qui ne montre que le contenu de
        // "data" — confirmé en direct le 2026-07-16 (premier appel réel avec une vraie clé : la
        // racine était bien un OBJECT, pas un ARRAY comme l'exemple de la doc le laissait croire).
        // "code" != 0 possible en théorie même sur un 200 HTTP (les codes d'erreur documentés sont
        // par ailleurs tous mappés à un statut HTTP non-200, déjà couvert par onStatus 4xx/5xx dans
        // fetch(), mais on vérifie quand même par robustesse plutôt que de supposer).
        if (!root.isObject()) {
            logger.warn("SoSoValue ({}) parsing failed at step 'envelope': expected a JSON object, got {}",
                    asset, root.getNodeType());
            return EtfFlowResponse.invalid();
        }
        // Vérifié en premier, avant même de regarder "data" : un "code" d'erreur peut légitimement
        // s'accompagner d'un "data" absent/null (cf. exemples doc "Error Responses"), ce n'est pas
        // en soi une anomalie de forme si le code l'explique déjà.
        int code = root.path("code").asInt(0);
        if (code != 0) {
            logger.warn("SoSoValue ({}) parsing failed at step 'envelope': code={} message='{}'",
                    asset, code, root.path("message").asText(""));
            return EtfFlowResponse.invalid();
        }
        if (!root.hasNonNull("data")) {
            logger.warn("SoSoValue ({}) parsing failed at step 'envelope': 'code' is 0 but 'data' "
                    + "field is missing/null", asset);
            return EtfFlowResponse.invalid();
        }

        JsonNode data = root.get("data");
        if (!data.isArray() || data.isEmpty()) {
            logger.warn("SoSoValue ({}) parsing failed at step 'body shape': expected non-empty array in "
                    + "'data', got {}", asset, data.getNodeType());
            return EtfFlowResponse.invalid();
        }

        JsonNode latest = data.get(0);
        if (!latest.hasNonNull("date")) {
            logger.warn("SoSoValue ({}) parsing failed at step 'date': field missing from first row", asset);
            return EtfFlowResponse.invalid();
        }
        if (!latest.hasNonNull("total_net_inflow")) {
            logger.warn("SoSoValue ({}) parsing failed at step 'total_net_inflow': field missing from first row",
                    asset);
            return EtfFlowResponse.invalid();
        }

        LocalDate date;
        try {
            date = LocalDate.parse(latest.get("date").asText());
        } catch (DateTimeParseException e) {
            logger.warn("SoSoValue ({}) parsing failed at step 'date format' on '{}': {}",
                    asset, latest.get("date").asText(), e.getMessage());
            return EtfFlowResponse.invalid();
        }

        double total = latest.get("total_net_inflow").asDouble();

        return EtfFlowResponse.builder()
                .valid(true)
                .date(date)
                .total(total)
                // Choix délibéré (cf. javadoc de classe) : jamais de détail par émetteur avec ce
                // client, map vide (pas null) pour rester compatible avec EtfFlowIndicator.compute.
                .byIssuer(Map.of())
                .build();
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("SoSoValue 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("SoSoValue 5xx: " + body));
    }
}
