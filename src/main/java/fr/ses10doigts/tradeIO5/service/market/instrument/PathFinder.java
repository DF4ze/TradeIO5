package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
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
 *       arête SELL {@code BASE -> QUOTE} (au bid). Toute paire touchant un nœud interdit (fiat) est ignorée.</li>
 *   <li>Sources = devises détenues (solde &gt; 0) hors nœuds interdits. Profondeur bornée par
 *       {@link ExecutionDefaults#MAX_PATH_DEPTH} (jamais explorée au-delà). À profondeur ≤ 2 l'énumération des chemins
 *       est exhaustive : le résultat est le minimum exact du coût (équivalent Dijkstra, sûr même avec un rebate négatif).</li>
 *   <li>Coût d'une jambe = frais du compte + demi-spread + slippage au montant (en %) ; chemin = <b>somme brute</b>.
 *       Chaque jambe est valorisée au même montant nominal (USD) : les nœuds intermédiaires sont des stables
 *       (cf. {@link LegMarketSource#accepts}).</li>
 *   <li>Taille : ⌊⌋ au {@code lotSz} pour un BUY (dépense ≤ montant), ⌈⌉ pour un SELL (couvrir le montant) ; contrôle
 *       {@code minSz} => BELOW_MIN. Prix de référence arrondi au tick (BUY vers le haut, SELL vers le bas).</li>
 *   <li>Sources suffisantes (solde ≥ montant) préférées ; sinon meilleure source détenue, {@code underfunded = true}.</li>
 *   <li>{@code preferredQuote} : si un chemin se termine par l'achat sur cette cotation, seuls ces chemins sont retenus
 *       (ex. consommer le stock USDT) ; sans effet sinon. Par défaut (null) : coût minimal pur.</li>
 * </ul>
 */
@Slf4j
@Component
public class PathFinder {

    private static final int PCT_SCALE = 6;

    /** Données de marché des jambes. {@code accepts} est un filtre sans réseau ; {@code market} peut appeler l'exchange. */
    public interface LegMarketSource {

        /** Cette arête peut-elle être valorisée ? (ex. cotation stable uniquement) */
        boolean accepts(InstrumentInfo instrument, LegSide side);

        /** Marché pour un montant nominal ; vide si indisponible (profondeur insuffisante...) : l'arête est écartée. */
        Optional<LegMarket> market(InstrumentInfo instrument, LegSide side, BigDecimal notionalAmount);
    }

    /**
     * @param sources devise détenue -> solde disponible
     * @param amount montant nominal à acquérir, en USD
     * @param preferredQuote cotation à préférer pour la dernière jambe, ou null
     */
    public record Request(List<InstrumentInfo> instruments, Set<String> forbiddenNodes, Map<String, BigDecimal> sources,
                          String target, BigDecimal amount, int maxDepth, String preferredQuote) {
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

        boolean funded = candidates.stream().anyMatch(c -> sources.get(c.source()).compareTo(request.amount()) >= 0);
        if (funded) {
            candidates = candidates.stream().filter(c -> sources.get(c.source()).compareTo(request.amount()) >= 0).toList();
        }
        if (request.preferredQuote() != null) {
            List<Candidate> preferred = candidates.stream()
                    .filter(c -> c.edges().getLast().from().equals(request.preferredQuote())).toList();
            if (!preferred.isEmpty()) {
                candidates = preferred;
            }
        }

        Map<String, Optional<LegMarket>> marketCache = new HashMap<>();
        final List<Candidate> pool = candidates;
        Optional<Priced> best = pool.stream()
                .map(c -> price(c, request, marketSource, marketCache))
                .flatMap(Optional::stream)
                .min(Comparator.comparing(Priced::totalCostPct)
                        .thenComparingInt(p -> p.legs().size())
                        .thenComparing(p -> p.legs().stream().map(PathLeg::instId).reduce("", String::concat)));
        if (best.isEmpty()) {
            return PathQuote.noPath(request.target(), request.amount());
        }

        Priced p = best.get();
        boolean belowMin = p.legs().stream().anyMatch(PathLeg::belowMin);
        PathQuote quote = new PathQuote(belowMin ? PathStatus.BELOW_MIN : PathStatus.OK, p.candidate().source(),
                request.target(), request.amount(), p.legs(), p.totalCostPct(), feeTest.level(p.totalCostPct()),
                p.estimation(), !funded);
        log.debug("PathFinder {} <- {} : {} jambes, coût {}%, {}", request.target(), quote.source(), p.legs().size(),
                p.totalCostPct(), quote.status());
        return quote;
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

    private Optional<Priced> price(Candidate candidate, Request request, LegMarketSource marketSource,
                                   Map<String, Optional<LegMarket>> marketCache) {
        List<PathLeg> legs = new ArrayList<>(candidate.edges().size());
        BigDecimal total = BigDecimal.ZERO;
        PathEstimation estimation = PathEstimation.BOOK;
        for (Edge edge : candidate.edges()) {
            String key = edge.instrument().instId() + ":" + edge.side();
            Optional<LegMarket> market = marketCache.computeIfAbsent(key,
                    k -> marketSource.market(edge.instrument(), edge.side(), request.amount()));
            if (market.isEmpty()) {
                return Optional.empty();
            }
            PathLeg leg = leg(edge, market.get(), request.amount());
            legs.add(leg);
            total = total.add(leg.costPct());
            if (market.get().estimation() == PathEstimation.TICKER) {
                estimation = PathEstimation.TICKER;
            }
        }
        return Optional.of(new Priced(candidate, legs, total.setScale(PCT_SCALE, RoundingMode.HALF_UP), estimation));
    }

    private static PathLeg leg(Edge edge, LegMarket market, BigDecimal amount) {
        InstrumentInfo i = edge.instrument();
        BigDecimal price = edge.side() == LegSide.BUY ? market.ask() : market.bid();
        BigDecimal sz = edge.side() == LegSide.BUY
                ? roundToStep(amount.divide(price, 18, RoundingMode.DOWN), i.lotSz(), RoundingMode.DOWN)
                : roundToStep(amount.divide(price, 18, RoundingMode.UP), i.lotSz(), RoundingMode.UP);
        return legOfSize(i, edge.side(), market, sz);
    }

    /** Jambe pour une taille donnée (déjà arrondie au lot) : coûts du marché, prix de référence au tick, contrôle du minimum. */
    public static PathLeg legOfSize(InstrumentInfo i, LegSide side, LegMarket market, BigDecimal sz) {
        BigDecimal halfSpread = market.ask().subtract(market.bid()).multiply(BigDecimal.valueOf(100))
                .divide(market.ask().add(market.bid()), PCT_SCALE, RoundingMode.HALF_UP);
        BigDecimal cost = market.feePct().add(halfSpread).add(market.slippagePct()).setScale(PCT_SCALE, RoundingMode.HALF_UP);
        BigDecimal price = side == LegSide.BUY ? market.ask() : market.bid();
        BigDecimal refPrice = roundToStep(price, i.tickSz(), side == LegSide.BUY ? RoundingMode.UP : RoundingMode.DOWN);
        boolean belowMin = sz.signum() == 0 || sz.compareTo(i.minSz()) < 0;
        return new PathLeg(i.instId(), side, sz, refPrice, market.feePct(), halfSpread, market.slippagePct(), cost, belowMin);
    }

    /** Arrondit {@code value} à un multiple de {@code step}. */
    public static BigDecimal roundToStep(BigDecimal value, BigDecimal step, RoundingMode mode) {
        return value.divide(step, 0, mode).multiply(step);
    }
}
