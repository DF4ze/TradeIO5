package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.dto.execution.RouteQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recherche du chemin d'achat le moins coûteux (service pur, aucun réseau : les données de marché arrivent par
 * {@link LegMarketSource}). Lecture seule, aucun ordre.
 * <ul>
 *   <li>Graphe orienté par exchange : pour chaque paire {@code BASE-QUOTE}, arête BUY {@code QUOTE -> BASE} (à l'ask) et
 *       arête SELL {@code BASE -> QUOTE} (au bid). Toute paire touchant un nœud interdit (fiat, USDT) est ignorée ; une
 *       arête n'existe que si {@link LegMarketSource#accepts} la valorise (cotation stable ou intermédiaire autorisé).</li>
 *   <li>Sources = devises détenues (solde &gt; 0) hors nœuds interdits. Profondeur bornée par
 *       {@link ExecutionDefaults#MAX_PATH_DEPTH}. À profondeur ≤ 2 l'énumération est exhaustive : le résultat est le
 *       minimum exact du coût.</li>
 *   <li>Coût d'une jambe = frais du compte + demi-spread + slippage au montant (en %) ; chemin = <b>somme brute</b>.
 *       Le notionnel de chaque jambe est converti en unités de sa cotation ({@code montant USD / prix USD de la devise}) ;
 *       la jambe suivante est dimensionnée sur le <b>reçu net</b> de la précédente.</li>
 *   <li>Taille : ⌊⌋ au {@code lotSz} ; contrôle {@code minSz} / {@code costMin} => BELOW_MIN. Prix de référence arrondi au
 *       tick (BUY vers le haut, SELL vers le bas).</li>
 *   <li>Sources suffisantes (solde valorisé en USD &ge; montant) préférées ; sinon meilleure source détenue,
 *       {@code underfunded = true}.</li>
 *   <li>Routes via un intermédiaire de {@link Request#displayOnly()} : chiffrées et listées dans {@code routes}, jamais choisies.</li>
 * </ul>
 */
@Slf4j
@Component
public class PathFinder {

    private static final int PCT_SCALE = 6;
    private static final int UNIT_SCALE = 18;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Données de marché des jambes. {@code accepts} est un filtre sans réseau ; {@code market} peut appeler l'exchange. */
    public interface LegMarketSource {

        /** Cette arête peut-elle être valorisée ? (ex. cotation stable ou intermédiaire autorisé) */
        boolean accepts(InstrumentInfo instrument, LegSide side);

        /** Marché pour un notionnel <b>en unités de la cotation</b> ; vide si indisponible (profondeur insuffisante...) : l'arête est écartée. */
        Optional<LegMarket> market(InstrumentInfo instrument, LegSide side, BigDecimal notionalAmount);

        /** Prix en USD d'une unité de la devise (stables : 1) ; vide si inconnu. Défaut : 1 (devises toutes stables). */
        default Optional<BigDecimal> usdPrice(String currency) {
            return Optional.of(BigDecimal.ONE);
        }
    }

    /**
     * @param sources devise détenue -> solde disponible (en unités de la devise)
     * @param amount montant nominal à acquérir, en USD
     * @param displayOnly intermédiaires chiffrés et affichés mais jamais choisis automatiquement (ex. ETH)
     */
    public record Request(List<InstrumentInfo> instruments, Set<String> forbiddenNodes, Map<String, BigDecimal> sources,
                          String target, BigDecimal amount, int maxDepth, Set<String> displayOnly) {

        public Request(List<InstrumentInfo> instruments, Set<String> forbiddenNodes, Map<String, BigDecimal> sources,
                       String target, BigDecimal amount, int maxDepth) {
            this(instruments, forbiddenNodes, sources, target, amount, maxDepth, Set.of());
        }
    }

    private record Edge(InstrumentInfo instrument, LegSide side) {
        String from() {
            return side == LegSide.BUY ? instrument.quote() : instrument.base();
        }

        String to() {
            return side == LegSide.BUY ? instrument.base() : instrument.quote();
        }
    }

    private record Candidate(String source, List<Edge> edges) {
    }

    private record Priced(Candidate candidate, List<PathLeg> legs, BigDecimal totalCostPct, PathEstimation estimation) {
        String routeKey() {
            StringBuilder key = new StringBuilder(candidate.source());
            candidate.edges().forEach(e -> key.append('>').append(e.to()));
            return key.toString();
        }

        /** Nœud intermédiaire (chemin à deux jambes) ou null. */
        String intermediate() {
            return candidate.edges().size() > 1 ? candidate.edges().getFirst().to() : null;
        }
    }

    private final FeeTest feeTest;

    public PathFinder(FeeTest feeTest) {
        this.feeTest = feeTest;
    }

    public PathQuote find(Request request, LegMarketSource marketSource) {
        int depth = Math.min(request.maxDepth(), ExecutionDefaults.MAX_PATH_DEPTH);
        Map<String, List<Edge>> edgesFrom = edgesFrom(request, marketSource);
        Map<String, BigDecimal> sources = sourcesOf(request);

        List<Candidate> found = new ArrayList<>();
        sources.keySet().forEach(source -> collect(source, source, request.target(), edgesFrom, depth, new ArrayList<>(), found));
        List<Candidate> candidates = found;
        if (candidates.isEmpty()) {
            return PathQuote.noPath(request.target(), request.amount());
        }

        Map<String, BigDecimal> sourceUsd = new HashMap<>();
        for (String source : sources.keySet()) {
            marketSource.usdPrice(source).ifPresent(price -> sourceUsd.put(source, sources.get(source).multiply(price)));
        }
        boolean funded = candidates.stream().anyMatch(c -> sourceUsd.getOrDefault(c.source(), BigDecimal.ZERO)
                .compareTo(request.amount()) >= 0);
        if (funded) {
            candidates = candidates.stream().filter(c -> sourceUsd.getOrDefault(c.source(), BigDecimal.ZERO)
                    .compareTo(request.amount()) >= 0).toList();
        }

        Map<String, Optional<LegMarket>> marketCache = new HashMap<>();
        List<Priced> priced = candidates.stream()
                .map(c -> price(c, request, marketSource, marketCache))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Priced::totalCostPct)
                        .thenComparingInt(p -> p.legs().size())
                        .thenComparing(p -> p.legs().stream().map(PathLeg::instId).reduce("", String::concat)))
                .toList();
        List<RouteQuote> routes = priced.stream().map(p -> route(p, request)).toList();
        Optional<Priced> best = priced.stream().filter(p -> isChosen(p, request)).findFirst();
        if (best.isEmpty()) {
            return PathQuote.noPath(request.target(), request.amount(), routes);
        }

        Priced p = best.get();
        boolean belowMin = p.legs().stream().anyMatch(PathLeg::belowMin);
        PathQuote quote = new PathQuote(belowMin ? PathStatus.BELOW_MIN : PathStatus.OK, p.candidate().source(),
                request.target(), request.amount(), p.legs(), p.totalCostPct(), feeTest.level(p.totalCostPct()),
                p.estimation(), !funded, p.routeKey(), routes);
        log.debug("PathFinder {} <- {} : {} jambes, coût {}%, {}", request.target(), quote.source(), p.legs().size(),
                p.totalCostPct(), quote.status());
        return quote;
    }

    private static boolean isChosen(Priced p, Request request) {
        return p.intermediate() == null || !request.displayOnly().contains(p.intermediate());
    }

    private RouteQuote route(Priced p, Request request) {
        List<String> warnings = new ArrayList<>();
        if (p.intermediate() != null) {
            warnings.add("Intermédiaire exposé : " + p.intermediate() + " détenu entre les deux jambes (risque de prix)");
            warnings.add("Liquidité croisée : " + p.legs().getLast().instId());
        }
        boolean belowMin = p.legs().stream().anyMatch(PathLeg::belowMin);
        return new RouteQuote(p.routeKey(), p.candidate().source(), p.legs(), p.totalCostPct(), feeTest.level(p.totalCostPct()),
                belowMin ? PathStatus.BELOW_MIN : PathStatus.OK, p.estimation(), isChosen(p, request), List.copyOf(warnings));
    }

    private static Map<String, List<Edge>> edgesFrom(Request request, LegMarketSource marketSource) {
        Map<String, List<Edge>> result = new HashMap<>();
        for (InstrumentInfo i : request.instruments()) {
            if (request.forbiddenNodes().contains(i.base()) || request.forbiddenNodes().contains(i.quote())) {
                continue;
            }
            for (LegSide side : LegSide.values()) {
                if (marketSource.accepts(i, side)) {
                    Edge edge = new Edge(i, side);
                    result.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
                }
            }
        }
        return result;
    }

    private static Map<String, BigDecimal> sourcesOf(Request request) {
        Map<String, BigDecimal> result = new HashMap<>();
        request.sources().forEach((currency, balance) -> {
            if (balance != null && balance.signum() > 0 && !request.forbiddenNodes().contains(currency)
                    && !currency.equals(request.target())) {
                result.put(currency, balance);
            }
        });
        return result;
    }

    private static void collect(String source, String node, String target, Map<String, List<Edge>> edgesFrom, int remaining,
                                List<Edge> path, List<Candidate> out) {
        if (remaining == 0) {
            return;
        }
        for (Edge edge : edgesFrom.getOrDefault(node, List.of())) {
            String next = edge.to();
            boolean visited = next.equals(source) || path.stream().anyMatch(e -> e.from().equals(next));
            if (visited) {
                continue;
            }
            path.add(edge);
            if (next.equals(target)) {
                out.add(new Candidate(source, List.copyOf(path)));
            } else {
                collect(source, next, target, edgesFrom, remaining - 1, path, out);
            }
            path.removeLast();
        }
    }

    /**
     * Chiffre un chemin jambe par jambe. Quantité détenue au départ = {@code montant USD / prix USD de la source} ; BUY :
     * 1ʳᵉ jambe {@code ⌊détenu/ask⌋}, jambes suivantes {@code ⌊reçu/(ask×(1+frais))⌋} ; SELL : {@code ⌊détenu⌋} au lot.
     * Reçu net : base × (1 − frais) après un BUY, cotation × bid × (1 − frais) après un SELL.
     */
    private Optional<Priced> price(Candidate candidate, Request request, LegMarketSource marketSource,
                                   Map<String, Optional<LegMarket>> marketCache) {
        Optional<BigDecimal> sourcePrice = marketSource.usdPrice(candidate.source());
        if (sourcePrice.isEmpty() || sourcePrice.get().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal held = request.amount().divide(sourcePrice.get(), UNIT_SCALE, RoundingMode.DOWN);
        List<PathLeg> legs = new ArrayList<>(candidate.edges().size());
        BigDecimal total = BigDecimal.ZERO;
        PathEstimation estimation = PathEstimation.BOOK;
        for (int i = 0; i < candidate.edges().size(); i++) {
            Edge edge = candidate.edges().get(i);
            InstrumentInfo instrument = edge.instrument();
            Optional<BigDecimal> notional = notional(edge, held, marketSource);
            if (notional.isEmpty()) {
                return Optional.empty();
            }
            String key = instrument.instId() + ":" + edge.side() + ":" + notional.get().stripTrailingZeros().toPlainString();
            Optional<LegMarket> market = marketCache.computeIfAbsent(key,
                    k -> marketSource.market(instrument, edge.side(), notional.get()));
            if (market.isEmpty()) {
                return Optional.empty();
            }
            BigDecimal fee = market.get().feePct().divide(HUNDRED, UNIT_SCALE, RoundingMode.HALF_UP);
            BigDecimal sz;
            if (edge.side() == LegSide.BUY) {
                BigDecimal divisor = i == 0 ? market.get().ask() : market.get().ask().multiply(BigDecimal.ONE.add(fee));
                sz = roundToStep(held.divide(divisor, UNIT_SCALE, RoundingMode.DOWN), instrument.lotSz(), RoundingMode.DOWN);
            } else {
                sz = roundToStep(held, instrument.lotSz(), RoundingMode.DOWN);
            }
            PathLeg leg = legOfSize(instrument, edge.side(), market.get(), sz);
            legs.add(leg);
            total = total.add(leg.costPct());
            if (market.get().estimation() == PathEstimation.TICKER) {
                estimation = PathEstimation.TICKER;
            }
            BigDecimal net = BigDecimal.ONE.subtract(fee);
            held = edge.side() == LegSide.BUY ? sz.multiply(net) : sz.multiply(market.get().bid()).multiply(net);
        }
        return Optional.of(new Priced(candidate, legs, total.setScale(PCT_SCALE, RoundingMode.HALF_UP), estimation));
    }

    /** Notionnel de la jambe en unités de sa cotation (BUY : le détenu ; SELL : le détenu en base converti en cotation). */
    private static Optional<BigDecimal> notional(Edge edge, BigDecimal held, LegMarketSource marketSource) {
        if (edge.side() == LegSide.BUY) {
            return Optional.of(held);
        }
        Optional<BigDecimal> basePrice = marketSource.usdPrice(edge.instrument().base());
        Optional<BigDecimal> quotePrice = marketSource.usdPrice(edge.instrument().quote());
        if (basePrice.isEmpty() || quotePrice.isEmpty() || quotePrice.get().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(held.multiply(basePrice.get()).divide(quotePrice.get(), UNIT_SCALE, RoundingMode.HALF_UP));
    }

    /** Jambe pour une taille donnée (déjà arrondie au lot) : coûts du marché, prix de référence au tick, contrôle du minimum. */
    public static PathLeg legOfSize(InstrumentInfo i, LegSide side, LegMarket market, BigDecimal sz) {
        BigDecimal halfSpread = market.ask().subtract(market.bid()).multiply(BigDecimal.valueOf(100))
                .divide(market.ask().add(market.bid()), PCT_SCALE, RoundingMode.HALF_UP);
        BigDecimal cost = market.feePct().add(halfSpread).add(market.slippagePct()).setScale(PCT_SCALE, RoundingMode.HALF_UP);
        BigDecimal price = side == LegSide.BUY ? market.ask() : market.bid();
        BigDecimal refPrice = roundToStep(price, i.tickSz(), side == LegSide.BUY ? RoundingMode.UP : RoundingMode.DOWN);
        boolean belowMin = sz.signum() == 0 || sz.compareTo(i.minSz()) < 0 || i.belowCostMin(sz.multiply(refPrice));
        return new PathLeg(i.instId(), side, sz, refPrice, market.feePct(), halfSpread, market.slippagePct(), cost, belowMin);
    }

    /** Arrondit {@code value} à un multiple de {@code step}. */
    public static BigDecimal roundToStep(BigDecimal value, BigDecimal step, RoundingMode mode) {
        return value.divide(step, 0, mode).multiply(step);
    }
}
