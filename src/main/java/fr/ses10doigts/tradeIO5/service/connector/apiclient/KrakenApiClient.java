package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.ProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceCacheManager;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;


@Component
public class KrakenApiClient implements ProviderApiClient, BalanceProvider {
    private static final Logger logger = LoggerFactory.getLogger(KrakenApiClient.class);

    private final BalanceCacheManager balanceCacheManager;

    private static final String API_URL = "https://api.kraken.com";
    private static final String API_VERSION = "0";

    private final WebClient webClient;

    public KrakenApiClient() {
        this.balanceCacheManager = new BalanceCacheManager();
        this.webClient = WebClient.builder()
                .baseUrl(API_URL)
                .build();
    }

    @Override
    public ProviderCode getProviderCode() {
        return ProviderCode.KRAKEN;
    }

    @Override
    public BigDecimal getBalance(String assetSymbol, ApiCredential credential) {
        Map<String, BigDecimal> all = getAllBalances(credential);
        return all.getOrDefault(assetSymbol.toUpperCase(), BigDecimal.ZERO);
    }

    @Override
    public Map<String, BigDecimal> getAllBalances(ApiCredential credential) {
        return balanceCacheManager.getBalances(credential.getApiKey() + ":" + credential.getProvider().getApiBaseUrl(),
                this, credential);
    }


    @Override
    public Map<String, BigDecimal> fetchAllBalances(ApiCredential credential) {
        try {
            JsonNode response = privatePost("Balance", Collections.emptyMap(), credential);

            if (response.has("error") && !response.get("error").isEmpty()) {
                throw new RuntimeException("Kraken API error: " + response.get("error"));
            }

            JsonNode result = response.get("result");
            Map<String, BigDecimal> balances = new HashMap<>();
            result.fieldNames().forEachRemaining(asset -> {
                BigDecimal amount = new BigDecimal(result.get(asset).asText());
                balances.put(normalizeAsset(asset), amount);
            });
            return balances;

        } catch (Exception e) {
            //throw new RuntimeException("Failed to get balances", e);
            logger.error("Failed to get balances {}", e.getMessage());
        }

        return new HashMap<>();
    }

    @Override
    public BigDecimal getMarketPrice(String assetSymbol, String quoteCurrency, ApiCredential credential) {
        return getMarketPriceRecur(assetSymbol, quoteCurrency, 0);
    }

    public BigDecimal getMarketPriceRecur(String assetSymbol, String quoteCurrency, int count) {
        String pair = "undefined";
        try {
            pair = getKrakenPair(assetSymbol, quoteCurrency);

            JsonNode response = publicGet("Ticker", Map.of("pair", pair));

            if (response.has("error") && !response.get("error").isEmpty()) {
                if( (""+response.get("error")).contains("Unknown asset pair") && count < 1 ){
                    count++;
                    return getMarketPriceRecur(assetSymbol, "USDX", count);
                }else {
                    throw new RuntimeException("Kraken API error: " + response.get("error"));
                }
            }

            JsonNode result = response.get("result");
            JsonNode ticker = result.elements().next();

            String priceStr = ticker.get("c").get(0).asText(); // "c" = last trade closed price
            return new BigDecimal(priceStr);
        } catch (Exception e) {
            logger.error("Failed to get market price for asset {}/{} (as Krk pair: {}): {}", assetSymbol, quoteCurrency, pair, e.getMessage());
            //throw new RuntimeException("Failed to get market price for asset "+assetSymbol+"/"+quoteCurrency, e);
        }
        return BigDecimal.ZERO;
    }


    @Override
    public List<TradeDto> getHistoricalTrades(Set<String> pairs, ApiCredential credential) {
        // Appeler getTradesSince avec date très ancienne
        return getTradesSince(LocalDateTime.of(2020,1,1,0,0), pairs, credential);
    }

    @Override
    public List<TradeDto> getTradesSince(LocalDateTime date, Set<String> pairs, ApiCredential credential) {
        List<TradeDto> trades = new ArrayList<>();
        try {
            long sinceEpoch = date.toEpochSecond(ZoneOffset.UTC) * 1000L; // Kraken demande en ms

            Map<String, String> params = new HashMap<>();
            params.put("start", String.valueOf(sinceEpoch));

            JsonNode response = privatePost("TradesHistory", params, credential);

            if (response.has("error") && !response.get("error").isEmpty()) {
                throw new RuntimeException("Kraken API error: " + response.get("error"));
            }

            JsonNode tradesNode = response.get("result").get("trades");
            Iterator<Map.Entry<String, JsonNode>> fields = tradesNode.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode tradeNode = entry.getValue();

                String pair = tradeNode.get("pair").asText();

                if (pairs == null || pairs.isEmpty() || pairs.contains(pair)) {
                    trades.add(mapKrakenTradeToTradeDto(tradeNode));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get trades", e);
        }
        return trades;
    }

    // ==== Utilitaires ====

    private TradeDto mapKrakenTradeToTradeDto(JsonNode tradeNode) {
        String pair = tradeNode.path("pair").asText();
        String asset = extractBaseAsset(pair);

        String tradeId = tradeNode.path("trade_id").asText();

        BigDecimal qty = new BigDecimal(tradeNode.path("vol").asText());
        BigDecimal price = new BigDecimal(tradeNode.path("price").asText());
        BigDecimal fee = new BigDecimal(tradeNode.path("fee").asText());

        long time = tradeNode.path("time").asLong();
        LocalDateTime timestamp = LocalDateTime.ofEpochSecond(time, 0, ZoneOffset.UTC);

        String type = tradeNode.path("type").asText();
        TradeSide side = "buy".equalsIgnoreCase(type) ? TradeSide.BUY : TradeSide.SELL;

        return TradeDto.builder()
                .tradeId(tradeId)
                .asset(asset)
                .quantity(qty)
                .price(price)
                .timestamp(timestamp)
                .side(side)
                .fee(fee)
                .build();
    }

    private String extractBaseAsset(String krakenPair) {
        if (krakenPair.length() < 6) return krakenPair;
        String base = krakenPair.substring(0, krakenPair.length() - 4);
        return switch (base) {
            case "XBT" -> "BTC";
            case "XXBT" -> "BTC";
            case "XETH" -> "ETH";
            case "XRP"  -> "XRP";
            case "XLTC" -> "LTC";
            default -> base.toUpperCase();
        };
    }

    private String getKrakenPair(String base, String quote) {
        return normalizeAsset(base) + normalizeAsset(quote);
    }

    private String normalizeAsset(String asset) {
        return switch (asset.toUpperCase()) {
            case "XXBT" -> "XBT";
            case "XBT.F" -> "XBT";
            case "BTC" -> "XBT";
            case "ETH" -> "ETH";
            case "EUR" -> "ZEUR";
            case "USDX" -> "USD";
            case "USD" -> "ZUSD";
            case "USDT" -> "USDT";
            default -> asset.toUpperCase();
        };
    }

    private JsonNode publicGet(String method, Map<String, String> params) throws Exception {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/" + API_VERSION + "/public/" + method);
        if (params != null && !params.isEmpty()) {
            params.forEach(uriBuilder::queryParam);
        }
        String uri = uriBuilder.toUriString();

        String body = webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(body);
    }

    public JsonNode privatePost(String method, Map<String, String> params, ApiCredential credential) throws Exception {
        String path = "/" + API_VERSION + "/private/" + method;

        long nonce = System.currentTimeMillis();
        Map<String, String> postData = new HashMap<>(params);
        postData.put("nonce", String.valueOf(nonce));

        String postDataEncoded = encodePostData(postData);

        String apiSign = generateSignature(path, nonce, postDataEncoded, credential.getSecretKey());

        String body = webClient.post()
                .uri(path)
                .header("API-Key", credential.getApiKey())
                .header("API-Sign", apiSign)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(postDataEncoded)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(body);
    }

    private String encodePostData(Map<String, String> data) {
        return data.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private String generateSignature(String path, long nonce, String postDataEncoded, String apiSecretBase64) throws Exception {
        // Kraken signature algo : HMAC-SHA512 of (path + SHA256(nonce + postData)) with key apiSecret (base64 decoded)
        byte[] decodedSecret = Base64.getDecoder().decode(apiSecretBase64);

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] noncePostData = (nonce + postDataEncoded).getBytes(StandardCharsets.UTF_8);
        byte[] sha256Hash = sha256.digest(noncePostData);

        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(pathBytes.length + sha256Hash.length);
        buffer.put(pathBytes);
        buffer.put(sha256Hash);
        byte[] message = buffer.array();

        Mac mac = Mac.getInstance("HmacSHA512");
        SecretKeySpec keySpec = new SecretKeySpec(decodedSecret, "HmacSHA512");
        mac.init(keySpec);
        byte[] macData = mac.doFinal(message);

        return Base64.getEncoder().encodeToString(macData);
    }


}
