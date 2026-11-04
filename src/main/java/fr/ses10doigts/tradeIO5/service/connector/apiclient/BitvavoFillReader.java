package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.trades.ExchangeFillReader;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Historique des remplissages spot Bitvavo : {@code GET /trades?market=…} (marché obligatoire), pages de 1000 du plus récent au
 * plus ancien par {@code end}. Bitvavo n'a pas d'historique tous marchés : les marchés lus sont {@code <devise détenue>} ×
 * {@code EUR / USDC / USDT} existants au catalogue public, les devises détenues venant du solde ({@code available} +
 * {@code inOrder}). <b>Limite connue</b> : les trades d'une devise entièrement vendue et absente du solde ne sont pas relus.
 * Lecture seule, clé « Voir ». Le frais est lu tel quel (négatif = remise).
 */
@Component
public class BitvavoFillReader implements ExchangeFillReader {

    static final String TRADES_PATH = "/trades";
    private static final Set<String> QUOTES = Set.of("EUR", "USDC", "USDT");
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_CALLS = 300;
    /** Poids 5 par appel pour 1000 points / minute : au plus ~150 appels / minute. */
    private static final Duration MIN_INTERVAL = Duration.ofMillis(450);

    private final long minIntervalNanos;
    private long lastCallNanos;

    @Autowired
    public BitvavoFillReader() {
        this(MIN_INTERVAL);
    }

    BitvavoFillReader(Duration minInterval) {
        this.minIntervalNanos = minInterval.toNanos();
        this.lastCallNanos = System.nanoTime() - minIntervalNanos;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.BITVAVO;
    }

    @Override
    public List<ImportedFill> fetchFills(ApiCredential credential, Instant since) {
        BitvavoRequests.requireComplete(credential);
        List<String> markets = markets(credential);
        List<ImportedFill> out = new ArrayList<>();
        int calls = 0;
        for (String market : markets) {
            Long end = null;
            while (true) {
                if (++calls > MAX_CALLS) {
                    throw new BalanceUnavailableException("Bitvavo : trop d'appels pour lire l'historique");
                }
                throttle();
                String path = TRADES_PATH + "?market=" + market + "&limit=" + PAGE_SIZE
                        + (since == null ? "" : "&start=" + since.toEpochMilli()) + (end == null ? "" : "&end=" + end);
                JsonNode rows = BitvavoRequests.requireJson(BitvavoRequests.get(credential, path), "trades " + market);
                if (!rows.isArray()) {
                    throw new BalanceUnavailableException("Bitvavo : liste de trades inattendue");
                }
                long oldest = Long.MAX_VALUE;
                for (JsonNode row : rows) {
                    ImportedFill fill = parse(row);
                    if (fill != null) {
                        out.add(fill);
                    }
                    oldest = Math.min(oldest, row.path("timestamp").asLong(Long.MAX_VALUE));
                }
                if (rows.size() < PAGE_SIZE || oldest == Long.MAX_VALUE) {
                    break;
                }
                end = oldest - 1;
            }
        }
        return out;
    }

    /** Marchés à relire : devises détenues × cotations connues, filtrés par le catalogue public. */
    private List<String> markets(ApiCredential credential) {
        Set<String> held = new LinkedHashSet<>();
        JsonNode balances = BitvavoRequests.requireJson(BitvavoRequests.get(credential, BitvavoBalanceReader.BALANCE_PATH), "balance");
        for (JsonNode entry : balances) {
            if (hasAmount(entry, "available") || hasAmount(entry, "inOrder")) {
                held.add(entry.path("symbol").asText("").toUpperCase());
            }
        }
        JsonNode catalog = BitvavoRequests.requireJson(BitvavoRequests.publicGet(credential.getWebProvider(),
                BitvavoInstrumentListClient.MARKETS_PATH), "markets");
        List<String> markets = new ArrayList<>();
        for (JsonNode item : catalog) {
            String base = item.path("base").asText("").toUpperCase();
            if (held.contains(base) && QUOTES.contains(item.path("quote").asText("").toUpperCase())) {
                markets.add(item.path("market").asText("").toUpperCase());
            }
        }
        return markets;
    }

    private static boolean hasAmount(JsonNode entry, String field) {
        try {
            return !entry.path(field).asText("").isBlank() && new BigDecimal(entry.path(field).asText()).signum() > 0;
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Bitvavo : solde illisible", e);
        }
    }

    private synchronized void throttle() {
        long wait = minIntervalNanos - (System.nanoTime() - lastCallNanos);
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BalanceUnavailableException("Bitvavo : attente interrompue", e);
            }
        }
        lastCallNanos = System.nanoTime();
    }

    /** Package-private pour les tests. {@code null} si la ligne est inexploitable. */
    static ImportedFill parse(JsonNode row) {
        String[] parts = row.path("market").asText("").split("-");
        String tradeId = row.path("id").asText("");
        if (parts.length != 2 || tradeId.isBlank()) {
            return null;
        }
        try {
            String fee = row.path("fee").asText("");
            String feeCcy = row.path("feeCurrency").asText("");
            String clOrdId = row.path("clientOrderId").asText("");
            return new ImportedFill(tradeId, row.path("orderId").asText(null), clOrdId.isBlank() ? null : clOrdId,
                    parts[0].toUpperCase(), parts[1].toUpperCase(),
                    "sell".equalsIgnoreCase(row.path("side").asText()) ? TradeSide.SELL : TradeSide.BUY,
                    new BigDecimal(row.path("price").asText()), new BigDecimal(row.path("amount").asText()),
                    fee.isBlank() ? BigDecimal.ZERO : new BigDecimal(fee), feeCcy.isBlank() ? null : feeCcy,
                    Instant.ofEpochMilli(row.path("timestamp").asLong()));
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Bitvavo : remplissage illisible (" + tradeId + ")", e);
        }
    }
}
