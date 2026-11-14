package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.AssetPlanOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanBlockReason;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Partie PURE du plan d'ordres (aucun I/O, aucun port d'ordre) partagée par {@link OrderPlanner} et le simulateur : carry,
 * minimum d'achat du lien, motif de blocage d'un devis d'achat, route de vente, net reçu / encaissé d'une route. Une seule
 * implémentation : ne jamais la dupliquer dans le rejeu.
 */
public final class OrderCore {

    private static final int AMOUNT_SCALE = 18;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private OrderCore() {
    }

    /** Mise du jour + carry entrant. */
    public static BigDecimal effectiveAmount(BigDecimal amountUsd, BigDecimal carryInUsd) {
        return amountUsd.add(carryInUsd == null ? BigDecimal.ZERO : carryInUsd);
    }

    /** Vrai si la mise effective est sous l'achat minimum de l'utilisateur (nul = aucun minimum) : achat reporté (carry). */
    public static boolean belowUserMin(BigDecimal effective, BigDecimal minBuyUsd) {
        return minBuyUsd != null && effective.compareTo(minBuyUsd) < 0;
    }

    /**
     * Carry sortant : un achat passé consomme le carry ; un achat BELOW_MIN reporte toute la mise effective ; tout autre cas
     * (vente, désactivé, rien à faire, autre blocage) laisse le carry inchangé. Jamais de sur-achat jusqu'au minimum.
     */
    public static BigDecimal carryOut(boolean buy, AssetPlanOutcome outcome, PlanBlockReason reason, BigDecimal carryIn,
                                      BigDecimal effective) {
        if (buy && outcome == AssetPlanOutcome.OK) {
            return BigDecimal.ZERO;
        }
        if (buy && outcome == AssetPlanOutcome.BLOCKED && reason == PlanBlockReason.BELOW_MIN) {
            return effective;
        }
        return carryIn;
    }

    /** Motif de blocage d'un devis d'achat, ou {@code null} si l'achat est faisable (statut OK, financé). */
    public static PlanBlockReason buyBlock(PathQuote quote, Map<String, BigDecimal> sources, String asset,
                                           List<InstrumentInfo> instruments, Set<String> stables, Set<String> forbiddenNodes) {
        if (quote.status() == PathStatus.NO_PATH) {
            return sources.values().stream().noneMatch(b -> b.signum() > 0)
                    ? PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES
                    : noRoute(asset, instruments, stables, forbiddenNodes) ? PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT
                    : PlanBlockReason.NO_PATH;
        }
        if (quote.underfunded()) {
            return PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES;
        }
        if (quote.status() == PathStatus.BELOW_MIN) {
            return PlanBlockReason.BELOW_MIN;
        }
        return null;
    }

    /** Vrai si l'actif n'a aucune paire exploitable (cotée en stable, ou en intermédiaire choisi lui-même coté en stable). */
    public static boolean noRoute(String asset, List<InstrumentInfo> instruments, Set<String> stables, Set<String> forbiddenNodes) {
        return instruments.stream().noneMatch(i -> i.base().equals(asset) && !forbiddenNodes.contains(i.quote())
                && (stables.contains(i.quote()) || viaChosenIntermediary(i.quote(), instruments, stables, forbiddenNodes)));
    }

    private static boolean viaChosenIntermediary(String quote, List<InstrumentInfo> instruments, Set<String> stables,
                                                 Set<String> forbiddenNodes) {
        return ExecutionDefaults.CHOSEN_INTERMEDIARIES.contains(quote) && instruments.stream()
                .anyMatch(i -> i.base().equals(quote) && stables.contains(i.quote()) && !forbiddenNodes.contains(i.quote()));
    }

    // ------------------------------------------------------------------ net reçu / encaissé

    /** Quantité d'actif réellement reçue après un achat : dernière jambe, {@code sz × (1 − frais)}. */
    public static BigDecimal netBaseReceived(List<PathLeg> legs) {
        PathLeg last = legs.getLast();
        return last.sz().multiply(BigDecimal.ONE.subtract(last.feePct().divide(HUNDRED, AMOUNT_SCALE, RoundingMode.HALF_UP)));
    }

    /** Net encaissé en USDC d'une vente : dernière jambe, {@code sz × prix de référence × (1 − frais)}. */
    public static BigDecimal netProceeds(List<PathLeg> legs) {
        PathLeg last = legs.getLast();
        return last.sz().multiply(last.refPrice())
                .multiply(BigDecimal.ONE.subtract(last.feePct().divide(HUNDRED, AMOUNT_SCALE, RoundingMode.HALF_UP)));
    }

    // ------------------------------------------------------------------ vente

    /** Une route de vente : jambes dans l'ordre et estimation la moins fiable. */
    public record SellRoute(List<PathLeg> legs, PathEstimation estimation) {
        public BigDecimal costPct() {
            return legs.stream().map(PathLeg::costPct).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** Route de vente choisie (la moins chère) ou, à défaut, le motif de blocage. */
    public record SellOutcome(Optional<SellRoute> route, PlanBlockReason emptyReason) {
    }

    /**
     * Vente en USDC : routes directes {@code actif -> stable}, sinon via un intermédiaire choisi ({@code actif -> I -> stable}) ;
     * la moins chère (puis la moins longue). Aucun marché lu ici : tout vient de {@code market}.
     */
    public static SellOutcome sellRoute(List<InstrumentInfo> instruments, Map<String, InstrumentInfo> byId, Set<String> stables,
                                   Set<String> forbiddenNodes, PathFinder.LegMarketSource market, String asset, BigDecimal qty,
                                   BigDecimal notionalUsd) {
        List<InstrumentInfo> direct = instruments.stream()
                .filter(i -> i.base().equals(asset) && stables.contains(i.quote()) && !forbiddenNodes.contains(i.quote()))
                .sorted(Comparator.comparing(InstrumentInfo::instId)).toList();
        List<SellRoute> routes = new ArrayList<>();
        for (InstrumentInfo pair : direct) {
            Optional<LegMarket> m = market.market(pair, LegSide.SELL, notionalUsd);
            if (m.isPresent()) {
                BigDecimal sz = PathFinder.roundToStep(qty, pair.lotSz(), RoundingMode.DOWN);
                routes.add(new SellRoute(List.of(PathFinder.legOfSize(pair, LegSide.SELL, m.get(), sz)), m.get().estimation()));
            }
        }
        if (direct.isEmpty()) {
            for (String intermediary : ExecutionDefaults.CHOSEN_INTERMEDIARIES.stream().sorted().toList()) {
                sellViaIntermediary(instruments, byId, stables, forbiddenNodes, market, asset, qty, notionalUsd, intermediary)
                        .ifPresent(routes::add);
            }
        }
        if (routes.isEmpty()) {
            return new SellOutcome(Optional.empty(), direct.isEmpty() && noRoute(asset, instruments, stables, forbiddenNodes)
                    ? PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT : PlanBlockReason.NO_PATH);
        }
        return new SellOutcome(routes.stream().min(Comparator.comparing(SellRoute::costPct)
                .thenComparingInt(r -> r.legs().size())), null);
    }

    /**
     * Vente {@code actif -> intermédiaire -> stable} (l'intermédiaire n'est jamais conservé) : jambe 1 {@code SELL actif-I} sur la
     * quantité, jambe 2 {@code SELL I-stable} dimensionnée sur le reçu net de la jambe 1 (⌊⌋ au lot).
     */
    private static Optional<SellRoute> sellViaIntermediary(List<InstrumentInfo> instruments, Map<String, InstrumentInfo> byId,
                                                           Set<String> stables, Set<String> forbiddenNodes,
                                                           PathFinder.LegMarketSource market, String asset, BigDecimal qty,
                                                           BigDecimal notionalUsd, String intermediary) {
        InstrumentInfo first = byId.get(asset + "-" + intermediary);
        Optional<InstrumentInfo> second = instruments.stream()
                .filter(i -> i.base().equals(intermediary) && stables.contains(i.quote()) && !forbiddenNodes.contains(i.quote()))
                .min(Comparator.comparing(InstrumentInfo::instId));
        Optional<BigDecimal> intermediaryUsd = market.usdPrice(intermediary);
        if (first == null || second.isEmpty() || intermediaryUsd.isEmpty()) {
            return Optional.empty();
        }
        Optional<LegMarket> market1 = market.market(first, LegSide.SELL, notionalUsd.divide(intermediaryUsd.get(), AMOUNT_SCALE,
                RoundingMode.HALF_UP));
        if (market1.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal sz1 = PathFinder.roundToStep(qty, first.lotSz(), RoundingMode.DOWN);
        PathLeg leg1 = PathFinder.legOfSize(first, LegSide.SELL, market1.get(), sz1);
        BigDecimal net1 = sz1.multiply(market1.get().bid())
                .multiply(BigDecimal.ONE.subtract(market1.get().feePct().divide(HUNDRED, AMOUNT_SCALE, RoundingMode.HALF_UP)));
        BigDecimal sz2 = PathFinder.roundToStep(net1, second.get().lotSz(), RoundingMode.DOWN);
        Optional<LegMarket> market2 = market.market(second.get(), LegSide.SELL, net1.multiply(intermediaryUsd.get()));
        if (market2.isEmpty()) {
            return Optional.empty();
        }
        PathLeg leg2 = PathFinder.legOfSize(second.get(), LegSide.SELL, market2.get(), sz2);
        PathEstimation estimation = market1.get().estimation() == PathEstimation.TICKER || market2.get().estimation() == PathEstimation.TICKER
                ? PathEstimation.TICKER : PathEstimation.BOOK;
        return Optional.of(new SellRoute(List.of(leg1, leg2), estimation));
    }
}
