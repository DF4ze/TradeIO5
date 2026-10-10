package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import lombok.RequiredArgsConstructor;

/**
 * Client OKX lecture seule pour la vue d'ensemble : soldes via {@link OkxBalanceReader}, prix via le ticker public
 * {@code /api/v5/market/ticker}. Pas d'historique de trades (retourne vide) ni de lecture de solde unitaire hors cache.
 */
@Component
@RequiredArgsConstructor
public class OkxApiClient implements ProviderApiClient {

    private static final Logger logger = LoggerFactory.getLogger(OkxApiClient.class);
    private static final String TICKER_PATH = "/api/v5/market/ticker";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkxBalanceReader balanceReader;

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public BigDecimal getBalance(String assetSymbol, ApiCredential credential) {
        return getAllBalances(credential).getOrDefault(assetSymbol.toUpperCase(), BigDecimal.ZERO);
    }

    @Override
    public Map<String, BigDecimal> getAllBalances(ApiCredential credential) {
        return balanceReader.getAvailableBalances(credential);
    }

    @Override
    public BigDecimal getMarketPrice(String assetSymbol, String quoteCurrency, ApiCredential credential) {
        if (assetSymbol.equalsIgnoreCase(quoteCurrency)) {
            return BigDecimal.ONE;
        }
        String instId = assetSymbol.toUpperCase() + "-" + quoteCurrency.toUpperCase();
        try {
            String body = WebClient.builder().baseUrl(credential.getWebProvider().getApiBaseUrl()).build()
                    .get().uri(u -> u.path(TICKER_PATH).queryParam("instId", instId).build())
                    .retrieve().bodyToMono(String.class).block(TIMEOUT);
            JsonNode last = MAPPER.readTree(body).path("data").path(0).path("last");
            return last.isMissingNode() ? BigDecimal.ZERO : new BigDecimal(last.asText());
        } catch (Exception e) {
            logger.warn("OKX : prix indisponible pour {} : {}", instId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @Override
    public List<TradeDto> getHistoricalTrades(Set<String> pairs, ApiCredential credential) {
        return List.of();
    }

    @Override
    public List<TradeDto> getTradesSince(LocalDateTime date, Set<String> pairs, ApiCredential credential) {
        return List.of();
    }
}
