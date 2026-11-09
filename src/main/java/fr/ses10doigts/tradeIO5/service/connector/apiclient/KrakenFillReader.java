package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.trades.ExchangeFillReader;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Historique des trades Kraken : {@code POST /0/private/TradesHistory}, pages de 50 ({@code ofs}), lecture seule (permission
 * « Query closed orders & trades »). Le <b>nonce</b> est sérialisé par clé dans {@link KrakenSignedRequests}.
 * <p>
 * Les achats/ventes faits via l'interface Kraken (Buy Crypto, achats récurrents « DCA », Convert) ne passent pas par le carnet d'ordres
 * et sont absents de {@code TradesHistory} : ils sont lus dans le <b>Ledger</b> ({@code POST /0/private/Ledgers}, permission « Query
 * ledger entries ») où chaque opération est une paire d'écritures {@code spend}/{@code receive} de même {@code refid}. Si la clé n'a pas
 * cette permission, seuls les trades du carnet sont importés (avertissement journalisé).
 */
@Slf4j
@Component
public class KrakenFillReader implements ExchangeFillReader {

    private static final int PAGE = 50;
    private static final int MAX_PAGES = 40;
    /** Du plus long au plus court : {@code XBTUSDC -> USDC}, {@code XXBTZUSD -> ZUSD}. */
    private static final List<String> QUOTES = List.of("USDC", "USDT", "ZUSD", "USD", "ZEUR", "EUR", "ZGBP", "GBP", "XXBT", "XBT", "XETH", "ETH");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;

    public KrakenFillReader(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.KRAKEN;
    }

    @Override
    public List<ImportedFill> fetchFills(ApiCredential credential, Instant since) {
        List<ImportedFill> out = new ArrayList<>();
        fetchPaged(credential, "TradesHistory", Map.of(), since, "trades", (id, node) -> {
            ImportedFill fill = parse(id, node);
            if (fill != null) {
                out.add(fill);
            }
        });
        try {
            out.addAll(fetchLedgerFills(credential, since));
        } catch (CredentialRejectedException e) {
            log.warn("Kraken : Ledgers refusé ({}) : achats faits via l'interface non importés — ajouter la permission « Query ledger entries » à la clé de lecture", e.getMessage());
        }
        return out;
    }

    /** Achats/ventes « Buy Crypto » / DCA / Convert : écritures {@code spend} + {@code receive} regroupées par {@code refid}. */
    private List<ImportedFill> fetchLedgerFills(ApiCredential credential, Instant since) {
        List<LedgerEntry> entries = new ArrayList<>();
        for (String type : List.of("spend", "receive")) {
            fetchPaged(credential, "Ledgers", Map.of("type", type), since, "ledger", (id, n) -> {
                try {
                    entries.add(new LedgerEntry(n.path("refid").asText(id), n.path("type").asText(type), n.path("asset").asText(""),
                            new BigDecimal(n.path("amount").asText("0")), new BigDecimal(n.path("fee").asText("0")),
                            Instant.ofEpochMilli((long) (n.path("time").asDouble() * 1000))));
                } catch (NumberFormatException e) {
                    throw new BalanceUnavailableException("Kraken : écriture de ledger illisible (" + id + ")", e);
                }
            });
        }
        return toFills(entries);
    }

    /** Écriture du Ledger Kraken ({@code amount} signé, {@code fee} positif, dans la devise {@code asset}). */
    record LedgerEntry(String refid, String type, String asset, BigDecimal amount, BigDecimal fee, Instant ts) {
    }

    private static final Set<String> QUOTE_ASSETS = Set.of("USD", "EUR", "GBP", "CAD", "JPY", "USDC", "USDT");

    /**
     * Apparie {@code spend}/{@code receive} par {@code refid} : cotation (USD, EUR, USDC...) dépensée => ACHAT du reçu ; reçue => VENTE
     * du dépensé. Les autres conversions (crypto contre crypto) et les écritures orphelines sont ignorées. Package-private (tests).
     */
    static List<ImportedFill> toFills(List<LedgerEntry> entries) {
        Map<String, LedgerEntry> spends = new LinkedHashMap<>();
        Map<String, LedgerEntry> receives = new LinkedHashMap<>();
        for (LedgerEntry e : entries) {
            (e.amount().signum() < 0 ? spends : receives).putIfAbsent(e.refid(), e);
        }
        List<ImportedFill> out = new ArrayList<>();
        for (Map.Entry<String, LedgerEntry> s : spends.entrySet()) {
            LedgerEntry spend = s.getValue();
            LedgerEntry receive = receives.get(s.getKey());
            Optional<String> spentSym = KrakenAssetNames.toSymbol(spend.asset());
            Optional<String> recvSym = receive == null ? Optional.empty() : KrakenAssetNames.toSymbol(receive.asset());
            if (spentSym.isEmpty() || recvSym.isEmpty() || receive.amount().signum() == 0) {
                continue;
            }
            boolean buy = QUOTE_ASSETS.contains(spentSym.get());
            if (buy == QUOTE_ASSETS.contains(recvSym.get())) {
                continue;   // crypto/crypto ou fiat/fiat : pas un trade valorisable ici
            }
            BigDecimal spent = spend.amount().abs();
            BigDecimal received = receive.amount();
            // quantité de base et montant de cotation, selon le sens
            BigDecimal baseQty = buy ? received : spent;
            BigDecimal quoteAmt = buy ? spent : received;
            BigDecimal px = quoteAmt.divide(baseQty, java.math.MathContext.DECIMAL64);
            BigDecimal feeQuote = buy ? spend.fee().add(receive.fee().multiply(px)) : receive.fee().add(spend.fee().multiply(px));
            out.add(new ImportedFill("ledger-" + s.getKey(), null, null, buy ? recvSym.get() : spentSym.get(), buy ? spentSym.get() : recvSym.get(),
                    buy ? TradeSide.BUY : TradeSide.SELL, px, baseQty, feeQuote, buy ? spentSym.get() : recvSym.get(), receive.ts()));
        }
        return out;
    }

    /** Parcourt un endpoint paginé de Kraken ({@code ofs}, pages de 50) et passe chaque entrée de {@code result.<key>} à {@code sink}. */
    private void fetchPaged(ApiCredential credential, String method, Map<String, String> extra, Instant since, String key,
                            java.util.function.BiConsumer<String, JsonNode> sink) {
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> params = new LinkedHashMap<>(extra);
            params.put("ofs", String.valueOf(offset));
            if (since != null) {
                params.put("start", String.valueOf(since.getEpochSecond()));
            }
            JsonNode response;
            try {
                response = MAPPER.readTree(KrakenSignedRequests.privatePost(clock, credential, method, params));
            } catch (BalanceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new BalanceUnavailableException("Kraken : réponse " + method + " illisible", e);
            }
            JsonNode errors = response.path("error");
            if (errors.isArray() && !errors.isEmpty()) {
                String message = "Kraken : erreur API " + errors;
                throw errors.toString().matches(".*(Invalid key|Invalid signature|Permission denied).*")
                        ? new CredentialRejectedException(message) : new BalanceUnavailableException(message);
            }
            JsonNode rows = response.path("result").path(key);
            int count = response.path("result").path("count").asInt(0);
            int seen = 0;
            for (Map.Entry<String, JsonNode> e : rows.properties()) {
                seen++;
                sink.accept(e.getKey(), e.getValue());
            }
            offset += seen;
            if (seen == 0 || offset >= count) {
                break;
            }
        }
    }

    /** Package-private pour les tests. {@code null} si la paire n'est pas décomposable (base/cotation inconnues). */
    static ImportedFill parse(String txid, JsonNode t) {
        // certaines paires arrivent séparées (« PAXG/USDC ») : le séparateur ne fait jamais partie du symbole
        String pair = t.path("pair").asText("").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.]", "");
        String quoteRaw = QUOTES.stream().filter(q -> pair.endsWith(q) && pair.length() > q.length()).findFirst().orElse(null);
        if (quoteRaw == null) {
            return null;
        }
        Optional<String> base = KrakenAssetNames.toSymbol(pair.substring(0, pair.length() - quoteRaw.length()));
        Optional<String> quote = KrakenAssetNames.toSymbol(quoteRaw);
        if (base.isEmpty() || quote.isEmpty()) {
            return null;
        }
        try {
            // frais Kraken : exprimés dans la devise de cotation
            return new ImportedFill(txid, t.path("ordertxid").asText(null), blankToNull(t.path("cl_ord_id").asText(null)), base.get(), quote.get(),
                    "sell".equalsIgnoreCase(t.path("type").asText()) ? TradeSide.SELL : TradeSide.BUY,
                    new BigDecimal(t.path("price").asText()), new BigDecimal(t.path("vol").asText()),
                    new BigDecimal(t.path("fee").asText("0")), quote.get(),
                    Instant.ofEpochMilli((long) (t.path("time").asDouble() * 1000)));
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Kraken : trade illisible (" + txid + ")", e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
