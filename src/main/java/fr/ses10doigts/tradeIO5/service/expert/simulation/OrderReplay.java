package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanBlockReason;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderCore;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exécution d'un ordre du rejeu, SANS I/O : même calcul de route que le plan d'ordres ({@link PathFinder#find},
 * {@link OrderCore}) sur un barème FIGÉ ({@link Schedule}) : frais taker, demi-spread et slippage estimés par instrument,
 * {@code lotSz} / {@code minSz} / {@code costMin} du catalogue, route imposée du lien. Les prix du jour viennent du rejeu.
 * Retourne la quantité réellement obtenue nette de frais, le cash réellement débité / encaissé et le carry sortant.
 */
public final class OrderReplay {

    private static final int SCALE = 18;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Coûts d'un instrument en % : frais taker, demi-spread et slippage (estimés une fois au lancement). */
    public record Quote(BigDecimal feePct, BigDecimal halfSpreadPct, BigDecimal slippagePct) {
    }

    /**
     * Barème figé d'un wallet. {@code currentUsd} : prix USD actuels des devises sans historique dans le rejeu (intermédiaire
     * BTC d'une route PAXG, par ex.) ; {@code warnings} : hypothèses à signaler dans le résultat.
     */
    public record Schedule(List<InstrumentInfo> instruments, Set<String> stables, Set<String> forbiddenNodes,
                           Map<String, Quote> quotes, Map<String, BigDecimal> currentUsd, List<String> warnings) {
    }

    /** Marché figé d'une journée : mid = prix USD base / prix USD cotation, bid/ask = mid ∓ demi-spread. */
    static final class FrozenLegMarketSource implements PathFinder.LegMarketSource {
        private final Schedule schedule;
        private final Map<String, Double> dayUsd;

        FrozenLegMarketSource(Schedule schedule, Map<String, Double> dayUsd) {
            this.schedule = schedule;
            this.dayUsd = dayUsd;
        }

        @Override
        public boolean accepts(InstrumentInfo instrument, LegSide side) {
            return schedule.stables().contains(instrument.quote()) || ExecutionDefaults.INTERMEDIARIES.contains(instrument.quote());
        }

        @Override
        public Optional<BigDecimal> usdPrice(String currency) {
            if (schedule.stables().contains(currency)) {
                return Optional.of(BigDecimal.ONE);
            }
            Double day = dayUsd.get(currency);
            if (day != null && day > 0) {
                return Optional.of(BigDecimal.valueOf(day));
            }
            return Optional.ofNullable(schedule.currentUsd().get(currency));
        }

        @Override
        public Optional<LegMarket> market(InstrumentInfo instrument, LegSide side, BigDecimal notional) {
            Quote q = schedule.quotes().get(instrument.instId());
            Optional<BigDecimal> base = usdPrice(instrument.base());
            Optional<BigDecimal> quote = usdPrice(instrument.quote());
            if (q == null || base.isEmpty() || quote.isEmpty() || quote.get().signum() <= 0) {
                return Optional.empty();
            }
            BigDecimal mid = base.get().divide(quote.get(), SCALE, RoundingMode.HALF_UP);
            BigDecimal hs = q.halfSpreadPct().divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            return Optional.of(new LegMarket(q.feePct(), mid.multiply(BigDecimal.ONE.subtract(hs)),
                    mid.multiply(BigDecimal.ONE.add(hs)), q.slippagePct(), PathEstimation.BOOK));
        }
    }

    /**
     * @param amountUsdc   BUY : mise nominale du jour (SizingCore) ; SELL : valeur nominale de la vente
     * @param quantity     SELL : quantité décidée par SizingCore (sinon ignorée)
     * @param carryIn      montant d'achat reporté (sous le minimum du lien)
     * @param cashAvailable cash USDC disponible pour l'achat (après les débits déjà faits ce jour)
     * @param minBuyUsd    achat minimum du lien (nul = aucun)
     * @param usd          prix USD du jour par actif
     */
    public record Request(String asset, RainbowLiveAction action, double amountUsdc, double quantity, double carryIn,
                          double cashAvailable, Double minBuyUsd, String preferredRoute, Map<String, Double> usd) {
    }

    /**
     * @param outcome      {@code OK}, {@code BELOW_MIN}, ou motif de blocage du plan ({@code NO_PATH},
     *                     {@code INSUFFICIENT_FUNDS_AFTER_FEES}, {@code NOT_TRADABLE_WITHOUT_FIAT})
     * @param cashDelta    cash réellement débité (&lt; 0, achat = mise effective) ou encaissé net de frais (&gt; 0, vente)
     * @param qtyDelta     quantité réellement obtenue nette de frais (&gt; 0, achat) ou vendue (&lt; 0)
     * @param costPct      coût total de la route en %, {@code costUsdc} = coût en USDC (mise × coût %)
     */
    public record Executed(String outcome, double effectiveAmount, double carryOut, double cashDelta, double qtyDelta,
                           double costPct, double costUsdc, String route, FeeTestLevel feeTestLevel) {
        static Executed none(double carryOut) {
            return new Executed("NO_ACTION", 0, carryOut, 0, 0, 0, 0, null, null);
        }

        public boolean executed() {
            return "OK".equals(outcome);
        }
    }

    private final Schedule schedule;
    private final PathFinder pathFinder;
    private final FeeTest feeTest;
    private final Map<String, InstrumentInfo> byId;

    public OrderReplay(Schedule schedule, FeeTest feeTest) {
        this.schedule = schedule;
        this.feeTest = feeTest;
        this.pathFinder = new PathFinder(feeTest);
        this.byId = schedule.instruments().stream().collect(Collectors.toMap(InstrumentInfo::instId, i -> i, (a, b) -> a));
    }

    public Schedule schedule() {
        return schedule;
    }

    public Executed execute(Request r) {
        PathFinder.LegMarketSource market = new FrozenLegMarketSource(schedule, r.usd());
        if (r.action() == RainbowLiveAction.BUY && r.amountUsdc() > 0) {
            return simulateBuy(r, market);
        }
        if (r.action() == RainbowLiveAction.SELL && r.quantity() > 0) {
            return simulateSell(r, market);
        }
        return Executed.none(r.carryIn());
    }

    private Executed simulateBuy(Request r, PathFinder.LegMarketSource market) {
        BigDecimal carryIn = BigDecimal.valueOf(r.carryIn());
        BigDecimal effective = OrderCore.effectiveAmount(BigDecimal.valueOf(r.amountUsdc()), carryIn);
        double eff = effective.doubleValue();
        BigDecimal minBuy = r.minBuyUsd() == null ? null : BigDecimal.valueOf(r.minBuyUsd());
        if (OrderCore.belowUserMin(effective, minBuy)) { // sous l'achat minimum du lien : reporté
            return new Executed(PlanBlockReason.BELOW_MIN.name(), eff, eff, 0, 0, 0, 0, null, null);
        }
        Map<String, BigDecimal> sources = new HashMap<>();
        sources.put(ExecutionDefaults.CASH_STABLE, BigDecimal.valueOf(Math.max(0.0, r.cashAvailable())));
        PathQuote quote = pathFinder.find(new PathFinder.Request(schedule.instruments(), schedule.forbiddenNodes(), sources,
                r.asset(), effective, ExecutionDefaults.MAX_PATH_DEPTH, displayOnly(), r.preferredRoute()), market);
        PlanBlockReason block = OrderCore.buyBlock(quote, sources, r.asset(), schedule.instruments(), schedule.stables(),
                schedule.forbiddenNodes());
        if (block != null) {
            double carryOut = block == PlanBlockReason.BELOW_MIN ? eff : r.carryIn(); // mise reportée seulement sous le minimum
            return new Executed(block.name(), eff, carryOut, 0, 0, 0, 0, quote.routeKey(), quote.feeTestLevel());
        }
        double cost = quote.totalCostPct().doubleValue();
        return new Executed("OK", eff, 0, -eff, OrderCore.netBaseReceived(quote.legs()).doubleValue(), cost, eff * cost / 100.0,
                quote.routeKey(), quote.feeTestLevel());
    }

    private Executed simulateSell(Request r, PathFinder.LegMarketSource market) {
        BigDecimal notional = r.amountUsdc() > 0 ? BigDecimal.valueOf(r.amountUsdc()) : BigDecimal.ONE;
        OrderCore.SellOutcome out = OrderCore.sellRoute(schedule.instruments(), byId, schedule.stables(), schedule.forbiddenNodes(),
                market, r.asset(), BigDecimal.valueOf(r.quantity()), notional);
        if (out.route().isEmpty()) {
            return new Executed(out.emptyReason().name(), 0, r.carryIn(), 0, 0, 0, 0, null, null);
        }
        OrderCore.SellRoute best = out.route().get();
        if (best.legs().stream().anyMatch(PathLeg::belowMin)) {
            return new Executed(PlanBlockReason.BELOW_MIN.name(), 0, r.carryIn(), 0, 0, 0, 0, null, null);
        }
        double cost = best.costPct().doubleValue();
        double proceeds = OrderCore.netProceeds(best.legs()).doubleValue();
        double sold = best.legs().getFirst().sz().doubleValue();
        String route = best.legs().stream().map(PathLeg::instId).collect(Collectors.joining(">"));
        return new Executed("OK", 0, r.carryIn(), proceeds, -sold, cost, notional.doubleValue() * cost / 100.0, route,
                feeTest.level(best.costPct()));
    }

    private static Set<String> displayOnly() {
        return ExecutionDefaults.INTERMEDIARIES.stream().filter(i -> !ExecutionDefaults.CHOSEN_INTERMEDIARIES.contains(i))
                .collect(Collectors.toUnmodifiableSet());
    }
}
