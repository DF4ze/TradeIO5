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
    /** Limite d'appels Kraken : ~2 points par page d'historique, le compteur redescend lentement ; pause entre pages et reprises sur « Rate limit exceeded ». */
    private static final long PAGE_PAUSE_MS = 3_000;
    private static final long RATE_LIMIT_WAIT_MS = 8_000;
    private static final int RATE_LIMIT_RETRIES = 4;
    /** Fenêtre minimale du Ledger en import incrémental (les achats DCA y sont peu nombreux). */
    private static final java.time.Duration LEDGER_MIN_WINDOW = java.time.Duration.ofDays(90);
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
            Instant ledgerSince = since == null ? null : java.util.Collections.min(List.of(since, clock.now().minus(LEDGER_MIN_WINDOW)));
            out.addAll(fetchLedgerFills(credential, ledgerSince));
        } catch (CredentialRejectedException e) {
            log.warn("Kraken : Ledgers refusé ({}) : achats faits via l'interface non importés — ajouter la permission « Query ledger entries » à la clé de lecture", e.getMessage());
        }
        return out;
    }

    /** Achats/ventes « Buy Crypto » / DCA / Convert : écritures {@code spend} + {@code receive} regroupées par {@code refid}. */
    private List<ImportedFill> fetchLedgerFills(ApiCredential credential, Instant since) {
        List<LedgerEntry> entries = new ArrayList<>();
        // le filtre type=spend renvoie déjà les deux écritures (spend + receive) de chaque opération : un seul appel suffit
        {
            fetchPaged(credential, "Ledgers", Map.of("type", "spend"), since, "ledger", (id, n) -> {
                try {
                    entries.add(new LedgerEntry(n.path("refid").asText(id), n.path("type").asText("spend"), n.path("asset").asText(""),
                            new BigDecimal(n.path("amount").asText("0")), new BigDecimal(n.path("fee").asText("0")),
                            Instant.ofEpochMilli((long) (n.path("time").asDouble() * 1000))));
                } catch (NumberFormatException e) {
                    throw new BalanceUnavailableException("Kraken : écriture de ledger illisible (" + id + ")", e);
                }
            });
        }
        List<ImportedFill> fills = toFills(entries);
        log.info("Kraken Ledgers : {} écriture(s) spend/receive lues, {} achat(s)/vente(s) appariés (autres ignorés : crypto/crypto ou orphelins)",
                entries.size(), fills.size());
        return fills;
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
        return markRecurring(out);
    }

    /** Écart toléré sur l'heure du jour entre deux exécutions d'un même achat récurrent. */
    private static final long RECURRING_TOLERANCE_SECONDS = 120;
    private static final long DAY_SECONDS = 86_400;

    /**
     * Marque « récurrent » (DCA de l'exchange) un trade du Ledger quand un autre trade de même paire, même sens et même montant de
     * cotation a eu lieu à la même heure du jour (± 2 min) un autre jour : le DCA automatique de Kraken s'exécute à heure fixe, une
     * conversion manuelle non. Package-private (tests).
     */
    static List<ImportedFill> markRecurring(List<ImportedFill> fills) {
        Map<String, List<ImportedFill>> groups = new LinkedHashMap<>();
        for (ImportedFill f : fills) {
            String key = f.base() + "/" + f.quote() + "/" + f.side() + "/" + f.px().multiply(f.sz()).setScale(2, java.math.RoundingMode.HALF_UP);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        java.util.Set<String> recurring = new java.util.HashSet<>();
        for (List<ImportedFill> group : groups.values()) {
            for (ImportedFill a : group) {
                for (ImportedFill b : group) {
                    long delta = Math.abs(a.ts().getEpochSecond() - b.ts().getEpochSecond());
                    long offset = Math.min(delta % DAY_SECONDS, DAY_SECONDS - delta % DAY_SECONDS);
                    if (delta >= DAY_SECONDS / 2 && offset <= RECURRING_TOLERANCE_SECONDS) {
                        recurring.add(a.tradeId());
                        break;
                    }
                }
            }
        }
        return fills.stream().map(f -> recurring.contains(f.tradeId()) ? f.asRecurring() : f).toList();
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
            JsonNode response = null;
            JsonNode errors = null;
            for (int attempt = 0; attempt <= RATE_LIMIT_RETRIES; attempt++) {
                try {
                    response = MAPPER.readTree(KrakenSignedRequests.privatePost(clock, credential, method, params));
                } catch (BalanceUnavailableException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BalanceUnavailableException("Kraken : réponse " + method + " illisible", e);
                }
                errors = response.path("error");
                if (attempt < RATE_LIMIT_RETRIES && errors.toString().contains("Rate limit exceeded")) {
                    pause(RATE_LIMIT_WAIT_MS);   // compteur Kraken (2 points/appel d'historique, décroissance lente) : on attend qu'il redescende
                    continue;
                }
                break;
            }
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
            pause(PAGE_PAUSE_MS);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BalanceUnavailableException("Kraken : import interrompu", e);
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
