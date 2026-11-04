package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.AssetPerformanceDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.HoldingsDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PnlSummaryDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioHistoryDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioPointDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioSummaryDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.TradeLineDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeOrigin;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveCandleSource;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vue d'accueil multi-wallets, scopée à l'utilisateur : soldes live (lecture seule, tous les wallets activés, avec ou sans passe),
 * historique de patrimoine reconstruit à rebours depuis les trades, et trades par exchange. Cours = clôtures D1 du moteur de
 * données (cache DB), jamais un appel de prix par exchange. {@code walletId} absent = tous les wallets ; wallet d'un autre
 * utilisateur ou inconnu ⇒ 404.
 */
@Slf4j
@Service
public class HomeOverviewService {

    private static final double EPSILON = 1e-12;
    private static final int D1_LOOKBACK = 720;
    private static final int MAX_TRADES = 300;
    private static final Duration PRICE_TTL = Duration.ofMinutes(10);
    private static final Duration PRICE_FAIL_TTL = Duration.ofMinutes(1);
    /** Stablecoins valorisés au nominal (1 USD), en plus des membres du groupe USD. */
    private static final Set<String> STABLES = PnlLedger.STABLES;

    private record PriceEntry(Instant at, NavigableMap<LocalDate, Double> closes) {
    }

    private record WalletRead(Wallet wallet, Map<String, Double> quantities) {
    }

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final Map<WebProviderCode, ReadOnlyBalanceReader> readers;
    private final AssetGroupService groupService;
    private final MarketDatasetEngine datasetEngine;
    private final DomainClock clock;
    private final Map<String, PriceEntry> priceCache = new ConcurrentHashMap<>();

    public HomeOverviewService(WalletRepository walletRepository, TransactionRepository transactionRepository,
                               List<ReadOnlyBalanceReader> readers, AssetGroupService groupService,
                               MarketDatasetEngine datasetEngine, DomainClock clock) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.readers = readers.stream().collect(Collectors.toMap(ReadOnlyBalanceReader::getProviderCode, Function.identity()));
        this.groupService = groupService;
        this.datasetEngine = datasetEngine;
        this.clock = clock;
    }

    // ---------- soldes live ----------

    public HoldingsDto holdings(User user, Long walletId) {
        List<Wallet> scope = scope(user, walletId);
        List<HoldingsDto.Unavailable> unavailable = new ArrayList<>();
        List<WalletRead> reads = read(scope, unavailable);
        Set<String> stables = stableSymbols();

        Map<String, double[]> qty = new LinkedHashMap<>();
        Map<String, List<HoldingsDto.Part>> parts = new HashMap<>();
        Map<String, Double> prices = new HashMap<>();
        Map<String, Set<MarketDataSource>> sources = sourcesOf(reads, List.of());
        boolean partial = false;
        for (WalletRead r : reads) {
            for (Map.Entry<String, Double> e : r.quantities().entrySet()) {
                String symbol = e.getKey();
                if (!prices.containsKey(symbol)) {
                    prices.put(symbol, stables.contains(symbol) ? Double.valueOf(1.0) : latestPrice(symbol, sources.get(symbol)));
                }
                Double price = prices.get(symbol);
                qty.computeIfAbsent(symbol, k -> new double[1])[0] += e.getValue();
                parts.computeIfAbsent(symbol, k -> new ArrayList<>()).add(new HoldingsDto.Part(r.wallet().getId(), r.wallet().getName(),
                        exchange(r.wallet()), e.getValue(), price == null ? null : e.getValue() * price));
            }
        }
        double stableTotal = 0, assetsTotal = 0;
        for (Map.Entry<String, double[]> e : qty.entrySet()) {
            Double price = prices.get(e.getKey());
            if (price == null) {
                partial = true;
            } else if (stables.contains(e.getKey())) {
                stableTotal += e.getValue()[0] * price;
            } else {
                assetsTotal += e.getValue()[0] * price;
            }
        }
        double total = stableTotal + assetsTotal;
        List<HoldingsDto.Line> lines = new ArrayList<>();
        for (Map.Entry<String, double[]> e : qty.entrySet()) {
            Double price = prices.get(e.getKey());
            Double value = price == null ? null : e.getValue()[0] * price;
            Double share = value == null || total <= EPSILON ? null : value / total * 100.0;
            lines.add(new HoldingsDto.Line(e.getKey(), e.getValue()[0], price, value, share, stables.contains(e.getKey()), parts.get(e.getKey())));
        }
        lines.sort(Comparator.comparing((HoldingsDto.Line l) -> l.value() == null ? -1.0 : l.value()).reversed());
        return new HoldingsDto(walletId, clock.now(), total, stableTotal, assetsTotal,
                total > EPSILON ? assetsTotal / total * 100.0 : null, partial, lines, unavailable);
    }

    // ---------- prix moyen d'achat ----------

    private record Computation(List<AssetPerformanceDto> assets, Map<String, PnlLedger.Result> ledgers) {
    }

    /**
     * Par actif (hors stables) : quantité, cours, valeur, prix moyen pondéré (PRU), <b>latent</b>, <b>réalisé</b> et frais, par wallet puis
     * agrégés. Les trades (manuels et TradeIO ; cotation non dollar convertie au cours D1 du jour du trade) sont rejoués par wallet x actif
     * ({@link PnlLedger}). Latent = quantité détenue (live) x (cours − PRU) ; prix moyen agrégé = moyenne des PRU pondérée par la quantité
     * détenue, de sorte que latent agrégé = somme des latents par wallet. Un actif entièrement vendu reste listé (réalisé conservé).
     */
    public List<AssetPerformanceDto> assetPerformance(User user, Long walletId) {
        return compute(user, walletId).assets();
    }

    /** Profits réalisés et latents, réalisé sur la période (ventes dont la date tombe dans la période) et cumulé. */
    public PnlSummaryDto pnlSummary(User user, Long walletId, PortfolioPeriod period) {
        Computation c = compute(user, walletId);
        LocalDate today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate from = period.from(today);
        double realized = 0, soldCost = 0, periodGain = 0, periodCost = 0, fees = 0, volume = 0, bought = 0;
        int periodSells = 0;
        boolean incomplete = false;
        for (PnlLedger.Result r : c.ledgers().values()) {
            realized += r.realized();
            soldCost += r.soldCost();
            fees += r.fees();
            volume += r.volume();
            bought += r.bought();
            incomplete |= r.incomplete();
            for (PnlLedger.Realization x : r.realizations()) {
                if (!x.at().toLocalDate().isBefore(from)) {
                    periodGain += x.amount();
                    periodCost += x.cost();
                    periodSells++;
                }
            }
        }
        double unrealized = 0, heldCost = 0;
        boolean anyLatent = false, partial = false;
        for (AssetPerformanceDto a : c.assets()) {
            for (AssetPerformanceDto.Part part : a.parts()) {
                if (part.pnl() != null) {
                    anyLatent = true;
                    unrealized += part.pnl();
                    heldCost += part.quantity() * part.avgBuyPrice();
                }
            }
            partial |= a.quantity() > EPSILON && a.pnl() == null;
        }
        Double total = anyLatent || soldCost > EPSILON ? realized + unrealized : null;
        double engaged = soldCost + heldCost;
        return new PnlSummaryDto(period.code(), from, realized, soldCost > EPSILON ? realized / soldCost * 100.0 : null, periodGain,
                periodCost > EPSILON ? periodGain / periodCost * 100.0 : null, periodSells, anyLatent ? unrealized : null,
                anyLatent && heldCost > EPSILON ? unrealized / heldCost * 100.0 : null, total,
                total != null && engaged > EPSILON ? total / engaged * 100.0 : null, fees, volume, bought, incomplete, partial);
    }

    private Computation compute(User user, Long walletId) {
        HoldingsDto holdings = holdings(user, walletId);
        Set<String> stables = stableSymbols();
        Map<String, List<PnlLedger.Fill>> fills = new HashMap<>();   // "walletId|symbole" -> trades en dollars
        Map<String, Double> invested = new HashMap<>();              // symbole -> achats − ventes
        Map<String, NavigableMap<LocalDate, Double>> closesBy = new HashMap<>();
        for (Wallet w : scope(user, walletId)) {
            MarketDataSource source = LiveCandleSource.of(w);
            for (Transaction t : transactionRepository.findByWalletOrderByTimestampAsc(w)) {
                String asset = assetOf(t);
                if (stables.contains(asset)) {
                    continue;
                }
                LocalDate day = t.getTimestamp().toLocalDate();
                // cotation non dollar (ex. PAXG acheté en BTC) : convertie au cours D1 de la devise de cotation le jour du trade
                Double rate = t.getQuoteCurrency() == null ? Double.valueOf(1.0) : usdAt(t.getQuoteCurrency(), day, source, stables, closesBy);
                if (rate == null) {
                    continue;   // pas de cours de conversion : trade ignoré plutôt que mal converti
                }
                // frais : en stable (1 $), dans la devise de cotation, ou convertis au cours D1 de leur devise ; inconnus ⇒ ignorés
                Double feeRate = t.getFeeCurrency() == null ? null
                        : t.getFeeCurrency().equalsIgnoreCase(t.getQuoteCurrency()) ? rate : usdAt(t.getFeeCurrency(), day, source, stables, closesBy);
                PnlLedger.Fill fill = PnlLedger.fill(t, asset, rate, feeRate);
                fills.computeIfAbsent(w.getId() + "|" + asset, k -> new ArrayList<>()).add(fill);
                invested.merge(asset, (fill.isBuy() ? 1 : -1) * fill.qty() * fill.priceUsd(), Double::sum);
            }
        }
        Map<String, PnlLedger.Result> ledgers = new HashMap<>();
        fills.forEach((k, f) -> ledgers.put(k, PnlLedger.replay(f)));

        Map<String, HoldingsDto.Line> lines = new LinkedHashMap<>();
        holdings.assets().stream().filter(l -> !l.stable()).forEach(l -> lines.put(l.assetSymbol(), l));
        ledgers.keySet().forEach(k -> lines.putIfAbsent(k.substring(k.indexOf('|') + 1), null));   // entièrement vendus : réalisé conservé

        List<AssetPerformanceDto> out = new ArrayList<>();
        Map<Long, Wallet> byId = new HashMap<>();
        scope(user, walletId).forEach(w -> byId.put(w.getId(), w));
        lines.forEach((symbol, line) -> out.add(performance(symbol, line, ledgers, invested.getOrDefault(symbol, 0.0), byId)));
        return new Computation(out, ledgers);
    }

    private static AssetPerformanceDto performance(String symbol, HoldingsDto.Line line, Map<String, PnlLedger.Result> ledgers, double invested,
                                                   Map<Long, Wallet> wallets) {
        Double price = line == null ? null : line.price();
        Map<Long, Double> held = new LinkedHashMap<>();   // walletId -> quantité détenue (live)
        if (line != null) {
            if (line.parts().isEmpty()) {
                held.put(null, line.quantity());
            }
            line.parts().forEach(p -> held.put(p.walletId(), p.quantity()));
        }
        ledgers.keySet().stream().filter(k -> k.endsWith("|" + symbol)).forEach(k -> held.putIfAbsent(Long.valueOf(k.substring(0, k.indexOf('|'))), 0.0));

        List<AssetPerformanceDto.Part> parts = new ArrayList<>();
        double realized = 0, soldCost = 0, fees = 0, volume = 0, bought = 0, heldCostKnown = 0, qtyKnown = 0, latent = 0;
        int sells = 0;
        boolean incomplete = false, anyLatent = false;
        for (Map.Entry<Long, Double> e : held.entrySet()) {
            PnlLedger.Result r = ledgers.get(e.getKey() + "|" + symbol);
            double q = e.getValue();
            Double avg = r == null ? null : r.avgPrice();
            boolean known = avg != null && avg > EPSILON && price != null;
            Double pnl = known ? q * (price - avg) : null;
            double rz = r == null ? 0 : r.realized(), sc = r == null ? 0 : r.soldCost();
            Double rzPct = sc > EPSILON ? rz / sc * 100.0 : null;
            int sl = r == null ? 0 : r.sells();
            Wallet wallet = wallets.get(e.getKey());
            parts.add(new AssetPerformanceDto.Part(e.getKey(), wallet == null ? null : wallet.getName(), wallet == null ? null : exchange(wallet), q, avg, pnl, known && q * avg > EPSILON ? pnl / (q * avg) * 100.0 : null, rz,
                    rzPct, pnl == null && sl == 0 ? null : rz + (pnl == null ? 0 : pnl), r == null ? 0 : r.fees(), r == null ? 0 : r.volume(), r == null ? 0 : r.bought(), sl, r != null && r.incomplete()));
            realized += rz;
            soldCost += sc;
            fees += r == null ? 0 : r.fees();
            volume += r == null ? 0 : r.volume();
            bought += r == null ? 0 : r.bought();
            sells += sl;
            incomplete |= r != null && r.incomplete();
            if (known) {
                anyLatent = true;
                latent += pnl;
                heldCostKnown += q * avg;
                qtyKnown += q;
            }
        }
        double quantity = line == null ? 0 : line.quantity();
        Double avgAgg = anyLatent && qtyKnown > EPSILON ? heldCostKnown / qtyKnown : null;
        Double pnl = anyLatent ? latent : null;
        Double total = pnl == null && sells == 0 ? null : realized + (pnl == null ? 0 : pnl);
        return new AssetPerformanceDto(symbol, quantity, price, line == null ? null : line.value(), invested, pnl,
                anyLatent && heldCostKnown > EPSILON ? latent / heldCostKnown * 100.0 : null, avgAgg, realized,
                soldCost > EPSILON ? realized / soldCost * 100.0 : null, total, fees, volume, bought, sells, incomplete, parts);
    }

    /** Valeur en dollars d'une unité de {@code symbol} au jour donné (stable = 1) ; nulle sans cours D1. */
    private Double usdAt(String symbol, LocalDate day, MarketDataSource source, Set<String> stables,
                         Map<String, NavigableMap<LocalDate, Double>> closesBy) {
        String sym = symbol.toUpperCase();
        if (stables.contains(sym)) {
            return 1.0;
        }
        var close = closesBy.computeIfAbsent(sym, q -> closes(q, source == null ? Set.of() : Set.of(source))).floorEntry(day);
        return close == null ? null : close.getValue();
    }

    // ---------- historique reconstruit ----------

    /** Hors transaction en lecture seule : le moteur de données écrit son cache de bougies en base. */
    public PortfolioHistoryDto history(User user, Long walletId, PortfolioPeriod period) {
        List<Wallet> scope = scope(user, walletId);
        LocalDate today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
        List<WalletRead> reads = read(scope, new ArrayList<>());
        Set<String> stables = stableSymbols();

        Map<String, Double> qty = new HashMap<>();
        reads.forEach(r -> r.quantities().forEach((s, q) -> qty.merge(s, q, Double::sum)));
        List<Transaction> trades = new ArrayList<>();
        scope.forEach(w -> trades.addAll(transactionRepository.findByWalletOrderByTimestampAsc(w)));
        trades.sort(Comparator.comparing(Transaction::getTimestamp));

        LocalDate floor = today.minusDays(D1_LOOKBACK - 1L);
        LocalDate from = period.from(today);
        if (period == PortfolioPeriod.ALL) {
            from = trades.isEmpty() ? today.minusDays(29) : trades.getFirst().getTimestamp().toLocalDate();
        }
        if (from.isBefore(floor)) {
            from = floor;
        }
        Map<LocalDate, List<Transaction>> byDay = trades.stream().collect(Collectors.groupingBy(t -> t.getTimestamp().toLocalDate()));
        Set<String> symbols = new HashSet<>(qty.keySet());
        trades.stream().map(Transaction::getAsset).forEach(symbols::add);
        Map<String, Set<MarketDataSource>> sources = sourcesOf(reads, trades);
        Map<String, NavigableMap<LocalDate, Double>> closes = new HashMap<>();
        symbols.stream().filter(s -> !stables.contains(s)).forEach(s -> closes.put(s, closes(s, sources.get(s))));

        double quoteAdj = 0;   // stable ajouté/retiré par les trades annulés (valeur nominale)
        List<PortfolioPointDto> series = new ArrayList<>();
        for (LocalDate day = today; !day.isBefore(from); day = day.minusDays(1)) {
            series.add(point(day, qty, quoteAdj, stables, closes));
            for (Transaction t : byDay.getOrDefault(day, List.of())) {
                quoteAdj += undo(t, qty, stables);
            }
        }
        java.util.Collections.reverse(series);
        return new PortfolioHistoryDto(walletId, period.code(), from, today, true, false, pnl(series), series);
    }

    private static PortfolioPointDto point(LocalDate day, Map<String, Double> qty, double quoteAdj, Set<String> stables,
                                           Map<String, NavigableMap<LocalDate, Double>> closes) {
        double stable = quoteAdj, assets = 0;
        for (Map.Entry<String, Double> e : qty.entrySet()) {
            double q = Math.max(0, e.getValue());
            if (q <= EPSILON) {
                continue;
            }
            if (stables.contains(e.getKey())) {
                stable += q;
                continue;
            }
            var close = closes.getOrDefault(e.getKey(), new TreeMap<>()).floorEntry(day);
            if (close == null) {
                return new PortfolioPointDto(day, null, null, null, null);
            }
            assets += q * close.getValue();
        }
        stable = Math.max(0, stable);
        double total = stable + assets;
        return new PortfolioPointDto(day, total, stable, assets, total > EPSILON ? assets / total * 100.0 : null);
    }

    /** Annule un trade sur les quantités ; renvoie la variation de stable à appliquer en remontant le temps. */
    private static double undo(Transaction t, Map<String, Double> qty, Set<String> stables) {
        if (stables.contains(assetOf(t))) {
            return 0;
        }
        boolean quoteIsStable = t.getQuoteCurrency() == null || stables.contains(t.getQuoteCurrency().toUpperCase());
        double q = t.getQuantity().doubleValue(), notional = quoteIsStable ? q * t.getPrice().doubleValue() : 0;
        double fee = t.getFee() == null ? 0 : t.getFee().doubleValue();
        boolean feeInAsset = t.getFeeCurrency() != null && t.getFeeCurrency().equalsIgnoreCase(assetOf(t));
        boolean feeInQuote = quoteIsStable && (t.getFeeCurrency() == null || stables.contains(t.getFeeCurrency().toUpperCase()));
        if (t.getSide() == TradeSide.BUY) {
            qty.merge(assetOf(t), -(q - (feeInAsset ? fee : 0)), Double::sum);
            return notional + (feeInQuote ? fee : 0);
        }
        qty.merge(assetOf(t), q + (feeInAsset ? fee : 0), Double::sum);
        return -(notional - (feeInQuote ? fee : 0));
    }

    private static PortfolioSummaryDto.Pnl pnl(List<PortfolioPointDto> series) {
        List<PortfolioPointDto> usable = series.stream().filter(p -> p.total() != null).toList();
        if (usable.size() < 2) {
            return null;
        }
        PortfolioPointDto first = usable.getFirst();
        double amount = usable.getLast().total() - first.total();
        return new PortfolioSummaryDto.Pnl(amount, first.total() > 0 ? amount / first.total() * 100.0 : null, first.day());
    }

    // ---------- trades par exchange ----------

    @Transactional(readOnly = true)
    public List<TradeLineDto> transactions(User user, Long walletId) {
        List<TradeLineDto> out = new ArrayList<>();
        Set<String> stables = stableSymbols();
        Map<String, NavigableMap<LocalDate, Double>> closesBy = new HashMap<>();
        for (Wallet w : scope(user, walletId)) {
            MarketDataSource source = LiveCandleSource.of(w);
            for (Transaction t : transactionRepository.findByWalletOrderByTimestampAsc(w)) {
                out.add(new TradeLineDto(t.getId(), t.getTimestamp(), t.getSide().name(), assetOf(t), t.getQuantity(), t.getPrice(),
                        t.getQuantity().multiply(t.getPrice()), t.getFee(), t.getFeeCurrency(), feeUsd(t, source, stables, closesBy),
                        w.getId(), w.getName(), exchange(w), t.getQuoteCurrency(), originOf(t).name()));
            }
        }
        out.sort(Comparator.comparing(TradeLineDto::timestamp).reversed());
        return out.size() > MAX_TRADES ? out.subList(0, MAX_TRADES) : out;
    }

    /** Frais d'un trade en dollars : stable = 1, devise de cotation ou actif du trade ⇒ converti à leur cours, autre ⇒ cours D1 du jour ; nul si sans frais ou cours inconnu. */
    private Double feeUsd(Transaction t, MarketDataSource source, Set<String> stables,
                          Map<String, NavigableMap<LocalDate, Double>> closesBy) {
        if (t.getFee() == null || t.getFee().signum() <= 0 || t.getFeeCurrency() == null) {
            return null;
        }
        LocalDate day = t.getTimestamp().toLocalDate();
        String ccy = t.getFeeCurrency().toUpperCase();
        Double quoteRate = t.getQuoteCurrency() == null ? Double.valueOf(1.0) : usdAt(t.getQuoteCurrency(), day, source, stables, closesBy);
        Double rate;
        if (ccy.equalsIgnoreCase(t.getQuoteCurrency())) {
            rate = quoteRate;
        } else if (ccy.equals(assetOf(t))) {
            rate = quoteRate == null ? null : t.getPrice().doubleValue() * quoteRate;
        } else {
            rate = usdAt(ccy, day, source, stables, closesBy);
        }
        return rate == null ? null : t.getFee().doubleValue() * rate;
    }

    /** Symbole d'un trade nettoyé (« PAXG/ » ⇒ « PAXG ») : seuls lettres, chiffres et point sont conservés. */
    static String assetOf(Transaction t) {
        return cleanSymbol(t.getAsset());
    }

    static String cleanSymbol(String symbol) {
        return symbol == null ? null : symbol.replaceAll("[^A-Za-z0-9.]", "").toUpperCase();
    }

    /** Provenance enregistrée ; pour un ancien trade sans tag : TradeIO s'il vient du FillRecorder (id préfixé « EXCHANGE: »), sinon manuel. */
    static TradeOrigin originOf(Transaction t) {
        if (t.getOrigin() != null) {
            return t.getOrigin();
        }
        return t.getExternalTransactionId() != null && t.getExternalTransactionId().contains(":") ? TradeOrigin.TRADEIO : TradeOrigin.MANUAL;
    }

    // ---------- outils ----------

    private List<Wallet> scope(User user, Long walletId) {
        List<Wallet> wallets = walletRepository.findByUserAndEnabledTrue(user);
        if (walletId == null) {
            return wallets;
        }
        return wallets.stream().filter(w -> w.getId().equals(walletId)).findFirst().map(List::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet inconnu : " + walletId));
    }

    /** Soldes disponibles par wallet (cache court des lecteurs) ; un wallet illisible est listé dans {@code unavailable}. */
    private List<WalletRead> read(Collection<Wallet> wallets, List<HoldingsDto.Unavailable> unavailable) {
        List<WalletRead> out = new ArrayList<>();
        for (Wallet w : wallets) {
            ReadOnlyBalanceReader reader = readers.get(w.getWebProviderCode());
            if (reader == null || w.getCredential() == null) {
                unavailable.add(new HoldingsDto.Unavailable(w.getId(), w.getName(), exchange(w),
                        reader == null ? "Exchange non pris en charge en lecture" : "Aucune clé de lecture"));
                continue;
            }
            try {
                Map<String, Double> q = new LinkedHashMap<>();
                reader.getAvailableBalances(w.getCredential()).forEach((s, v) -> {
                    if (v != null && v.doubleValue() > EPSILON) {
                        q.merge(s, v.doubleValue(), Double::sum);
                    }
                });
                out.add(new WalletRead(w, q));
            } catch (RuntimeException e) {
                log.warn("Accueil : soldes indisponibles wallet={} : {}", w.getId(), e.getMessage());
                unavailable.add(new HoldingsDto.Unavailable(w.getId(), w.getName(), exchange(w), "Soldes indisponibles"));
            }
        }
        return out;
    }

    private Set<String> stableSymbols() {
        Set<String> s = new HashSet<>(STABLES);
        s.addAll(groupService.members(AssetGroup.USD));
        return s;
    }

    private static String exchange(Wallet w) {
        return w.getWebProviderCode() == null ? null : w.getWebProviderCode().name();
    }

    /** Exchanges de marché où chaque actif est détenu ou a été tradé : repli de valorisation quand l'actif n'est pas configuré. */
    private static Map<String, Set<MarketDataSource>> sourcesOf(List<WalletRead> reads, List<Transaction> trades) {
        Map<String, Set<MarketDataSource>> out = new HashMap<>();
        for (WalletRead r : reads) {
            MarketDataSource source = LiveCandleSource.of(r.wallet());
            if (source != null) {
                r.quantities().keySet().forEach(s -> out.computeIfAbsent(s, k -> new java.util.LinkedHashSet<>()).add(source));
            }
        }
        for (Transaction t : trades) {
            MarketDataSource source = LiveCandleSource.of(t.getWallet());
            if (source != null) {
                out.computeIfAbsent(assetOf(t), k -> new java.util.LinkedHashSet<>()).add(source);
            }
        }
        return out;
    }

    private Double latestPrice(String symbol, Set<MarketDataSource> sources) {
        var last = closes(symbol, sources).lastEntry();
        return last == null ? null : last.getValue();
    }

    /** Clôtures D1 d'un actif (cache mémoire 10 min ; un échec n'est retenu qu'1 min). */
    private NavigableMap<LocalDate, Double> closes(String symbol, Set<MarketDataSource> sources) {
        Instant now = clock.now();
        PriceEntry cached = priceCache.get(symbol);
        if (cached != null && Duration.between(cached.at(), now).compareTo(cached.closes().isEmpty() ? PRICE_FAIL_TTL : PRICE_TTL) <= 0) {
            return cached.closes();
        }
        NavigableMap<LocalDate, Double> map = new TreeMap<>();
        DailyDataset daily = null;
        try {
            daily = datasetEngine.getDailyDatasetForAsset(symbol, D1_LOOKBACK, now, null);
        } catch (RuntimeException e) {
            log.debug("Accueil : {} non configuré ({}), repli sur l'exchange où il est détenu", symbol, e.getMessage());
        }
        // repli : l'actif lu sur un exchange se valorise chez ce même exchange (sans créer de configuration)
        for (MarketDataSource source : daily == null && sources != null ? sources : Set.<MarketDataSource>of()) {
            try {
                daily = datasetEngine.getDailyDatasetOnSource(symbol, source, D1_LOOKBACK, now);
                break;
            } catch (RuntimeException e) {
                log.warn("Accueil : cours D1 indisponibles pour {} sur {} : {}", symbol, source, e.getMessage());
            }
        }
        if (daily != null && daily.dataset() != null && daily.dataset().getMarketDatas() != null) {
            for (MarketData c : daily.dataset().getMarketDatas()) {
                BigDecimal close = c.getClose();
                if (close != null && close.signum() > 0) {
                    map.put(c.getTimestamp().atOffset(ZoneOffset.UTC).toLocalDate(), close.doubleValue());
                }
            }
        }
        if (map.isEmpty()) {
            log.warn("Accueil : cours D1 indisponibles pour {}", symbol);
        }
        priceCache.put(symbol, new PriceEntry(now, map));
        return map;
    }
}
