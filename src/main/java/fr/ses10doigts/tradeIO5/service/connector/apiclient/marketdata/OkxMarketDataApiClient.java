package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.MarketDataProviderException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Implémentation {@link MarketDataApiClient} pour OKX.
 * <p>
 * Endpoint public GET /api/v5/market/candles : aucune signature requise, contrairement aux
 * endpoints privés (balances, ordres) qui nécessiteraient un {@code ApiCredential}.
 * <p>
 * OKX est le seul des 3 providers dont la limite d'historique est un choix d'implémentation
 * réparable, pas une contrainte d'exchange (cf. docs/etudes/etude-fallback-multi-provider-marketdata.md
 * §1) : {@code /market/candles} (endpoint "récent") est plafonné empiriquement à 300 bougies
 * quel que soit {@code limit} demandé (vérifié le 2026-08-10 : {@code limit=301} renvoie le même
 * nombre de bougies que {@code limit=300}) — au-delà, cette classe route en interne vers
 * {@code /market/history-candles} (curseurs {@code after}/{@code before}, même format JSON) pour
 * combler le passé manquant. Le contrat {@link MarketDataApiClient} n'est pas affecté : le
 * routing reste entièrement interne à cette classe.
 */
@Component
public class OkxMarketDataApiClient implements MarketDataApiClient {

    private static final Logger logger = LoggerFactory.getLogger(OkxMarketDataApiClient.class);

    private static final String API_URL = "https://www.okx.com";
    private static final String CANDLES_PATH = "/api/v5/market/candles";
    private static final String HISTORY_CANDLES_PATH = "/api/v5/market/history-candles";

    // Seul TimeFrame ingéré nativement par Bucket aujourd'hui (cf. Bucket.BASE_TIME_FRAME)
    private static final Map<TimeFrame, String> NATIVE_BARS = Map.of(TimeFrame.H1, "1H");

    // OKX error codes, doc publique "Error Code" : 51001 = "Instrument ID does not exist".
    private static final String ERROR_CODE_INSTRUMENT_NOT_FOUND = "51001";

    private final WebClient webClient;

    public OkxMarketDataApiClient() {
        this.webClient = WebClient.builder().baseUrl(API_URL).build();
    }

    @Override
    public MarketDataSource getSource() {
        return MarketDataSource.OKX;
    }

    @Override
    public List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit) {
        String bar = nativeBar(timeFrame);
        int effectiveLimit = limit > 0 ? limit : 100;

        try {
            List<MarketData> recent = fetchCandles(CANDLES_PATH, symbol, bar, timeFrame, since, until, effectiveLimit);

            List<MarketData> merged = mergeWithHistory(recent, since,
                    oldest -> fetchHistoryPage(symbol, bar, timeFrame, oldest, effectiveLimit));

            List<MarketData> result = merged.stream()
                    .sorted(Comparator.comparing(MarketData::getTimestamp))
                    .distinct()
                    .toList();

            if (result.size() > effectiveLimit) {
                result = result.subList(result.size() - effectiveLimit, result.size());
            }
            return result;
        } catch (MarketDataProviderException e) {
            logger.warn("Failed to fetch OKX candles for {} ({}) : {}", symbol, bar, e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.warn("Failed to fetch OKX candles for {} ({}) : {}", symbol, bar, e.getMessage());
            throw new ProviderUnavailableException(MarketDataSource.OKX, symbol, e.getMessage(), e);
        }
    }

    /**
     * Appelle {@code /market/candles} (fenêtre récente, plafonnée à ~300 bougies) avec la même
     * convention {@code after}/{@code before} qu'auparavant (déjà "à l'envers" du sens intuitif
     * côté OKX : {@code after}=borne haute demandée, {@code before}=borne basse demandée — conservé
     * tel quel, cf. historique de cette classe).
     */
    private List<MarketData> fetchCandles(String path, String symbol, String bar, TimeFrame timeFrame,
                                           Instant since, Instant until, int limit) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromPath(path)
                .queryParam("instId", symbol)
                .queryParam("bar", bar)
                .queryParam("limit", limit);
        if (until != null) {
            uriBuilder.queryParam("after", until.toEpochMilli());
        }
        if (since != null) {
            uriBuilder.queryParam("before", since.toEpochMilli());
        }

        String body = webClient.get()
                .uri(uriBuilder.toUriString())
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseCandles(body, symbol, timeFrame);
    }

    /**
     * Une page de {@code /market/history-candles}, curseur {@code after} = timestamp de la bougie
     * la plus ancienne déjà obtenue (convention OKX : {@code after} renvoie des bougies antérieures
     * à ce timestamp — vérifié empiriquement le 2026-08-10, même format de réponse que
     * {@code /market/candles}).
     */
    private List<MarketData> fetchHistoryPage(String symbol, String bar, TimeFrame timeFrame, Instant after, int limit) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromPath(HISTORY_CANDLES_PATH)
                .queryParam("instId", symbol)
                .queryParam("bar", bar)
                .queryParam("limit", limit)
                .queryParam("after", after.toEpochMilli());

        String body = webClient.get()
                .uri(uriBuilder.toUriString())
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseCandles(body, symbol, timeFrame);
    }

    /**
     * Enveloppe {@link #mapCandlesResponse} (déclarée {@code throws Exception} pour rester
     * appelable directement en test avec un payload à la main) afin de l'utiliser depuis un
     * contexte qui ne peut pas déclarer d'exception checked (lambda {@link Function} passée à
     * {@link #mergeWithHistory}). Les {@link MarketDataProviderException} (unchecked) déjà levées
     * par {@code mapCandlesResponse} traversent telles quelles ; toute autre {@link Exception}
     * checked inattendue (parsing JSON malformé, etc.) est enveloppée en
     * {@link ProviderUnavailableException}.
     */
    private List<MarketData> parseCandles(String body, String symbol, TimeFrame timeFrame) {
        try {
            return mapCandlesResponse(body, symbol, timeFrame);
        } catch (MarketDataProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderUnavailableException(MarketDataSource.OKX, symbol, e.getMessage(), e);
        }
    }

    /**
     * Complète {@code recent} (résultat de {@code /market/candles}) en paginant vers
     * {@code /market/history-candles} tant que la bougie la plus ancienne obtenue est encore
     * postérieure à {@code since}. S'arrête dès qu'une page est vide (fin réelle de l'historique
     * disponible pour ce symbole chez OKX — cas légitime, pas une erreur) ou que le curseur
     * n'avance plus (garde-fou anti-boucle infinie). Si {@code since} est {@code null}, ne pagine
     * jamais (comportement "fenêtre récente" inchangé).
     * <p>
     * Extraite en méthode statique prenant un {@link Function} pour être testable indépendamment
     * de l'appel réseau, à l'image de {@link #mapCandlesResponse}.
     */
    static List<MarketData> mergeWithHistory(List<MarketData> recent, Instant since,
                                              Function<Instant, List<MarketData>> historyPageFetcher) {
        if (since == null) {
            return recent;
        }

        List<MarketData> merged = new ArrayList<>(recent);
        Instant oldest = oldestTimestamp(merged);
        if (oldest == null) {
            return merged;
        }

        while (oldest.isAfter(since)) {
            List<MarketData> page = historyPageFetcher.apply(oldest);
            if (page.isEmpty()) {
                break;
            }
            merged.addAll(page);

            Instant pageOldest = oldestTimestamp(page);
            if (pageOldest == null || !pageOldest.isBefore(oldest)) {
                break;
            }
            oldest = pageOldest;
        }
        return merged;
    }

    private static Instant oldestTimestamp(List<MarketData> candles) {
        return candles.stream()
                .map(MarketData::getTimestamp)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * Mappe le tableau OKX ([ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]) vers une liste
     * de {@link MarketData}. Isolée de l'appel réseau pour être testable en unitaire. Réutilisée
     * telle quelle pour {@code /market/history-candles} : format de réponse identique (vérifié
     * empiriquement le 2026-08-10).
     * <p>
     * Le champ {@code code} porte le signal d'erreur OKX. {@code "51001"} ("Instrument ID does not
     * exist") est le seul cas permanent distingué ici → {@link SymbolNotFoundException}. Tout
     * autre code non-{@code "0"} (ex: rate limit {@code 50011}, erreurs serveur {@code 5xxxx}) est
     * traité comme transitoire → {@link ProviderUnavailableException}.
     */
    static List<MarketData> mapCandlesResponse(String body, String symbol, TimeFrame timeFrame) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(body);

        if (response.has("code") && !"0".equals(response.get("code").asText())) {
            String code = response.get("code").asText();
            String message = "OKX API error: " + response.path("msg").asText();
            if (ERROR_CODE_INSTRUMENT_NOT_FOUND.equals(code)) {
                throw new SymbolNotFoundException(MarketDataSource.OKX, symbol, message);
            }
            throw new ProviderUnavailableException(MarketDataSource.OKX, symbol, message, null);
        }

        List<MarketData> candles = new ArrayList<>();
        JsonNode data = response.get("data");
        if (data == null) {
            return candles;
        }

        for (JsonNode candle : data) {
            // [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]
            long ts = candle.get(0).asLong();

            candles.add(MarketData.builder()
                    .pair(symbol)
                    .timeFrame(timeFrame)
                    .timestamp(Instant.ofEpochMilli(ts))
                    .open(new BigDecimal(candle.get(1).asText()))
                    .high(new BigDecimal(candle.get(2).asText()))
                    .low(new BigDecimal(candle.get(3).asText()))
                    .close(new BigDecimal(candle.get(4).asText()))
                    .volume(new BigDecimal(candle.get(5).asText()))
                    .build());
        }

        // OKX renvoie les bougies du plus récent au plus ancien : on remet en ordre chronologique
        return candles.stream()
                .sorted(Comparator.comparing(MarketData::getTimestamp))
                .toList();
    }

    static String nativeBar(TimeFrame timeFrame) {
        String bar = NATIVE_BARS.get(timeFrame);
        if (bar == null) {
            throw new IllegalArgumentException(
                    "OKX market data client only supports TimeFrame.H1 natively, got: " + timeFrame);
        }
        return bar;
    }
}
