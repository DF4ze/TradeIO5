package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.dto.execution.RouteQuote;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.execution.AssetPlanOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.execution.ExchangeLegMarketSource;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Construit le plan d'ordres (dry-run) d'un utilisateur pour une passe, <b>sans aucun port d'ordre et sans écriture</b> :
 * lectures de (a) seulement (catalogue en base, frais du compte en cache, carnet public). Service pur vis-à-vis de la base :
 * les entrées arrivent en {@link AssetInput}.
 * <ul>
 *   <li>Par wallet, un <b>bilan virtuel</b> par membre du groupe USD (USDC) est initialisé du cash du snapshot de la passe et
 *       parcouru par {@code priority} croissante, comme le {@code CashLedger} du bench. Les ventes ne le créditent pas.</li>
 *   <li>Achat : {@link PathFinder} choisit la route la moins chère (directe {@code USDC -> actif}, ou {@code USDC -> BTC -> actif}
 *       via un intermédiaire choisi ; ETH est chiffré et affiché, jamais choisi). Un stock en transit du wallet visant l'actif
 *       voit sa jambe 2 terminée <b>avant</b> tout nouvel achat.</li>
 *   <li>Vente : cotation stable directe, sinon {@code actif -> BTC -> USDC} (l'intermédiaire n'est jamais conservé).</li>
 *   <li>Fee Test : WARNING et RED => étapes + avertissement (jamais de blocage).</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderPlanner {

    private static final int AMOUNT_SCALE = 18;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InstrumentCatalog catalog;
    private final Map<WebProviderCode, TradingFeeProvider> feeProviders;
    private final Map<WebProviderCode, OrderBookClient> bookClients;
    private final AssetGroupService groupService;
    private final PathFinder pathFinder;
    private final FeeTest feeTest;
    private final BigDecimal slippageTolerancePct;
    private final Set<String> forbiddenNodes;

    @Autowired
    public OrderPlanner(InstrumentCatalog catalog, List<TradingFeeProvider> feeProviders, List<OrderBookClient> bookClients,
                        AssetGroupService groupService, PathFinder pathFinder, FeeTest feeTest, ExecutionSettings settings,
                        @Value("${" + ExecutionDefaults.FORBIDDEN_NODES_PROPERTY + ":}") String forbiddenNodes) {
        this.catalog = catalog;
        this.feeProviders = feeProviders.stream().collect(Collectors.toMap(TradingFeeProvider::getProviderCode, Function.identity()));
        this.bookClients = bookClients.stream().collect(Collectors.toMap(OrderBookClient::getProviderCode, Function.identity()));
        this.groupService = groupService;
        this.pathFinder = pathFinder;
        this.feeTest = feeTest;
        this.slippageTolerancePct = settings.getSlippageTolerancePct();
        this.forbiddenNodes = ExecutionDefaults.forbiddenNodes(forbiddenNodes);
    }

    /** Contexte d'un wallet pour un plan : catalogue, marché (carnets lus une fois) et bilan virtuel du groupe USD. */
    private static final class WalletContext {
        private final List<InstrumentInfo> instruments;
        private final Map<String, InstrumentInfo> byId;
        private final Set<String> stables;
        private final ExchangeLegMarketSource market;
        private final Map<String, BigDecimal> virtual = new LinkedHashMap<>();

        private WalletContext(List<InstrumentInfo> instruments, Set<String> stables, ExchangeLegMarketSource market,
                              Map<String, BigDecimal> cash) {
            this.instruments = instruments;
            this.byId = instruments.stream().collect(Collectors.toMap(InstrumentInfo::instId, Function.identity(), (a, b) -> a));
            this.stables = stables;
            this.market = market;
            stables.stream().sorted().forEach(s -> virtual.put(s, cash.getOrDefault(s, BigDecimal.ZERO).max(BigDecimal.ZERO)));
        }

        private BigDecimal held(String member) {
            return virtual.getOrDefault(member, BigDecimal.ZERO);
        }

        private void spend(String member, BigDecimal amount) {
            virtual.put(member, held(member).subtract(amount).max(BigDecimal.ZERO));
        }
    }

    /** Ordre de passage du cash : priorité du lien, puis actif, puis wallet. */
    private static final Comparator<AssetInput> INPUT_ORDER = Comparator.comparingInt(AssetInput::priority)
            .thenComparing(AssetInput::assetSymbol)
            .thenComparing(AssetInput::walletId, Comparator.nullsFirst(Comparator.naturalOrder()));

    public PlanDraft plan(List<AssetInput> inputs) {
        List<AssetInput> ordered = inputs.stream()
                .sorted(INPUT_ORDER).toList();
        Map<Long, WalletContext> contexts = new HashMap<>();
        Map<Long, Map<String, BigDecimal>> pools = new HashMap<>();
        List<PlanDraft.AssetDraft> drafts = new ArrayList<>();
        for (AssetInput input : ordered) {
            drafts.add(withCarry(input, planAsset(input, contexts, pools)));
        }
        return summarize(drafts);
    }

    /**
     * Carry (achat sous le minimum du provider) : l'achat BELOW_MIN reporte toute la mise effective (jour + carry entrant) ; un
     * achat passé le consomme ; tout autre cas (vente, désactivé, rien à faire, autre blocage) laisse le carry inchangé.
     */
    private static PlanDraft.AssetDraft withCarry(AssetInput in, PlanDraft.AssetDraft draft) {
        BigDecimal carryIn = in.carryInUsd();
        boolean buy = in.action() == RainbowLiveAction.BUY;
        if (buy && draft.outcome() == AssetPlanOutcome.OK) {
            return draft.withCarry(carryIn, BigDecimal.ZERO);
        }
        if (buy && draft.outcome() == AssetPlanOutcome.BLOCKED && draft.blockReason() == PlanBlockReason.BELOW_MIN) {
            return draft.withCarry(carryIn, effectiveAmount(in));
        }
        return draft.withCarry(carryIn, carryIn);
    }

    /** Mise du jour + carry entrant. */
    private static BigDecimal effectiveAmount(AssetInput in) {
        return in.amountUsd().add(in.carryInUsd());
    }

    private PlanDraft.AssetDraft planAsset(AssetInput in, Map<Long, WalletContext> contexts,
                                           Map<Long, Map<String, BigDecimal>> pools) {
        if (!in.executionEnabled()) {
            return draft(in, AssetPlanOutcome.DISABLED, null, null, null, null, List.of());
        }
        if (in.status() == PortfolioStatus.NOT_TRADABLE) {
            return blocked(in, PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT);
        }
        if (in.status() != PortfolioStatus.OK) {
            return blocked(in, PlanBlockReason.READING_UNAVAILABLE);
        }
        if (in.action() != RainbowLiveAction.BUY && in.action() != RainbowLiveAction.SELL) {
            return draft(in, AssetPlanOutcome.NO_ACTION, null, null, null, null, List.of());
        }
        try {
            // pool du wallet : cash du snapshot du premier actif servi (le bilan virtuel est ensuite mis à jour en mémoire)
            pools.computeIfAbsent(in.walletId(), id -> in.cashByMember());
            WalletContext ctx = contexts.computeIfAbsent(in.walletId(), id -> newContext(in, pools.get(id)));
            return in.action() == RainbowLiveAction.BUY ? planBuy(in, ctx) : planSell(in, ctx);
        } catch (RuntimeException e) {
            log.warn("Plan d'ordres : marché ou catalogue indisponible pour {} (wallet {}) : {}", in.assetSymbol(),
                    in.walletId(), e.getMessage());
            return blocked(in, PlanBlockReason.READING_UNAVAILABLE);
        }
    }

    private WalletContext newContext(AssetInput in, Map<String, BigDecimal> cash) {
        List<String> members = groupService.members(AssetGroup.USD);
        Set<String> stables = Set.copyOf(members);
        List<InstrumentInfo> instruments = catalog.liveInstruments(in.credential().getWebProvider());
        ExchangeLegMarketSource market = new ExchangeLegMarketSource(in.provider(), in.credential(), stables, instruments,
                feeProviders.get(in.provider()), bookClients.get(in.provider()));
        return new WalletContext(instruments, stables, market, cash);
    }

    // ------------------------------------------------------------------ achat

    private PlanDraft.AssetDraft planBuy(AssetInput in, WalletContext ctx) {
        if (in.amountUsd() == null || in.amountUsd().signum() <= 0) {
            return draft(in, AssetPlanOutcome.NO_ACTION, null, null, null, null, List.of());
        }
        BigDecimal amount = effectiveAmount(in);
        List<PlanDraft.StepDraft> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        finishTransitLegs(in, ctx, steps, notes);

        Map<String, BigDecimal> sources = new HashMap<>(ctx.virtual);
        PathQuote quote = pathFinder.find(new PathFinder.Request(ctx.instruments, forbiddenNodes, sources, in.assetSymbol(), amount,
                ExecutionDefaults.MAX_PATH_DEPTH, displayOnlyIntermediaries()), ctx.market);
        if (quote.status() == PathStatus.NO_PATH) {
            PlanBlockReason reason = sources.values().stream().noneMatch(b -> b.signum() > 0)
                    ? PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES
                    : noRoute(in.assetSymbol(), ctx) ? PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT : PlanBlockReason.NO_PATH;
            return blocked(in, reason);
        }
        if (quote.underfunded()) {
            return blocked(in, PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES);
        }
        if (quote.status() == PathStatus.BELOW_MIN) {
            return blocked(in, PlanBlockReason.BELOW_MIN);
        }
        FeeTestLevel level = quote.feeTestLevel();
        for (int i = 0; i < quote.legs().size(); i++) {
            PathLeg leg = quote.legs().get(i);
            BigDecimal quoteAmount = i == 0 ? amount : leg.sz().multiply(leg.refPrice());
            steps.add(step(steps.size() + 1, in.assetSymbol(), leg, quoteAmount, quote.estimation(), ctx));
        }
        ctx.spend(quote.source(), amount);
        notes.addAll(routeNotes(quote));
        String warning = warnings(level, quote.totalCostPct(), quote.estimation(), notes.isEmpty() ? null : String.join(" ; ", notes));
        log.info("Plan d'ordres achat {} : {} étape(s) route {} coût={}% Fee Test={}", in.assetSymbol(), steps.size(),
                quote.routeKey(), quote.totalCostPct(), level);
        return new PlanDraft.AssetDraft(in.assetSymbol(), in.priority(), in.walletId(), in.action(), AssetPlanOutcome.OK, null,
                quote.totalCostPct(), level, warning, steps);
    }

    /**
     * Termine d'abord la jambe 2 de chaque stock en transit du wallet visant l'actif : achat ({@code BUY <actif>-<devise>}, taille
     * ⌊stock / (ask × (1 + frais))⌋) ou vente ({@code SELL <devise>-<stable>}, taille ⌊stock⌋ au lot). Jamais d'ordre
     * compensatoire ; ces étapes précèdent tout nouvel ordre. Une jambe sous le minimum de l'exchange est signalée, le stock
     * reste en transit.
     */
    private void finishTransitLegs(AssetInput in, WalletContext ctx, List<PlanDraft.StepDraft> steps, List<String> notes) {
        boolean sell = in.action() == RainbowLiveAction.SELL;
        for (Map.Entry<String, BigDecimal> stock : new TreeMap<>(in.transitStock()).entrySet()) {
            BigDecimal quantity = stock.getValue();
            String currency = stock.getKey();
            Optional<InstrumentInfo> pair = sell
                    ? ctx.instruments.stream().filter(i -> i.base().equals(currency) && ctx.stables.contains(i.quote())
                            && !forbiddenNodes.contains(i.quote())).min(Comparator.comparing(InstrumentInfo::instId))
                    : Optional.ofNullable(ctx.byId.get(in.assetSymbol() + "-" + currency));
            if (quantity == null || quantity.signum() <= 0 || pair.isEmpty()) {
                notes.add("Stock en transit " + currency + " ignoré (aucune paire " + (sell ? currency + "-stable" : in.assetSymbol() + "-" + currency) + ")");
                continue;
            }
            LegSide legSide = sell ? LegSide.SELL : LegSide.BUY;
            Optional<LegMarket> market = ctx.market.market(pair.get(), legSide, sell
                    ? quantity.multiply(ctx.market.usdPrice(currency).orElse(BigDecimal.ONE)) : quantity);
            if (market.isEmpty()) {
                notes.add("Stock en transit " + currency + " : carnet trop peu profond, jambe 2 reportée");
                continue;
            }
            BigDecimal sz;
            if (sell) {
                sz = PathFinder.roundToStep(quantity, pair.get().lotSz(), RoundingMode.DOWN);
            } else {
                BigDecimal fee = market.get().feePct().divide(HUNDRED, AMOUNT_SCALE, RoundingMode.HALF_UP);
                sz = PathFinder.roundToStep(quantity.divide(market.get().ask().multiply(BigDecimal.ONE.add(fee)), AMOUNT_SCALE,
                        RoundingMode.DOWN), pair.get().lotSz(), RoundingMode.DOWN);
            }
            PathLeg leg = PathFinder.legOfSize(pair.get(), legSide, market.get(), sz);
            if (leg.belowMin()) {
                notes.add("Stock en transit " + currency + " sous le minimum de " + pair.get().instId() + " : conservé");
                continue;
            }
            steps.add(step(steps.size() + 1, in.assetSymbol(), leg, quantity, market.get().estimation(), ctx));
            notes.add("Termine d'abord la jambe 2 du stock en transit " + currency + " (" + pct(quantity) + ")");
            log.info("Plan d'ordres {} : jambe 2 du stock en transit {} {} ajoutée en tête", in.assetSymbol(), pct(quantity), currency);
        }
    }

    /** Avertissements de la route choisie et routes chiffrées mais non choisies (ex. ETH). */
    private static List<String> routeNotes(PathQuote quote) {
        List<String> notes = new ArrayList<>();
        quote.routes().stream().filter(r -> r.routeKey().equals(quote.routeKey())).findFirst()
                .ifPresent(r -> notes.addAll(r.warnings()));
        quote.routes().stream().filter(r -> !r.chosen())
                .forEach(r -> notes.add("Route " + r.routeKey() + " non choisie : " + pct(r.totalCostPct()) + " %"));
        return notes;
    }

    private static Set<String> displayOnlyIntermediaries() {
        return ExecutionDefaults.INTERMEDIARIES.stream().filter(i -> !ExecutionDefaults.CHOSEN_INTERMEDIARIES.contains(i))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Vrai si l'actif n'a aucune paire exploitable (cotée en stable, ou en intermédiaire choisi lui-même coté en stable). */
    private boolean noRoute(String asset, WalletContext ctx) {
        return ctx.instruments.stream().noneMatch(i -> i.base().equals(asset) && !forbiddenNodes.contains(i.quote())
                && (ctx.stables.contains(i.quote()) || viaChosenIntermediary(i.quote(), ctx)));
    }

    private boolean viaChosenIntermediary(String quote, WalletContext ctx) {
        return ExecutionDefaults.CHOSEN_INTERMEDIARIES.contains(quote) && ctx.instruments.stream()
                .anyMatch(i -> i.base().equals(quote) && ctx.stables.contains(i.quote()) && !forbiddenNodes.contains(i.quote()));
    }

    // ------------------------------------------------------------------ vente

    private PlanDraft.AssetDraft planSell(AssetInput in, WalletContext ctx) {
        BigDecimal qty = in.quantity();
        if (qty == null || qty.signum() <= 0) {
            return draft(in, AssetPlanOutcome.NO_ACTION, null, null, null, null, List.of());
        }
        BigDecimal notionalUsd = in.amountUsd() != null && in.amountUsd().signum() > 0 ? in.amountUsd() : BigDecimal.ONE;
        List<PlanDraft.StepDraft> transitSteps = new ArrayList<>();
        List<String> transitNotes = new ArrayList<>();
        finishTransitLegs(in, ctx, transitSteps, transitNotes);
        List<InstrumentInfo> direct = ctx.instruments.stream()
                .filter(i -> i.base().equals(in.assetSymbol()) && ctx.stables.contains(i.quote()) && !forbiddenNodes.contains(i.quote()))
                .sorted(Comparator.comparing(InstrumentInfo::instId)).toList();

        List<SellRoute> routes = new ArrayList<>();
        for (InstrumentInfo pair : direct) {
            Optional<LegMarket> market = ctx.market.market(pair, LegSide.SELL, notionalUsd);
            if (market.isPresent()) {
                BigDecimal sz = PathFinder.roundToStep(qty, pair.lotSz(), RoundingMode.DOWN);
                routes.add(new SellRoute(List.of(PathFinder.legOfSize(pair, LegSide.SELL, market.get(), sz)), market.get().estimation()));
            }
        }
        if (direct.isEmpty()) {
            for (String intermediary : ExecutionDefaults.CHOSEN_INTERMEDIARIES.stream().sorted().toList()) {
                sellViaIntermediary(in, ctx, qty, notionalUsd, intermediary).ifPresent(routes::add);
            }
        }
        if (routes.isEmpty()) {
            return blocked(in, direct.isEmpty() && noRoute(in.assetSymbol(), ctx) ? PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT
                    : PlanBlockReason.NO_PATH);
        }
        SellRoute best = routes.stream().min(Comparator.comparing(SellRoute::costPct)
                .thenComparingInt(r -> r.legs().size())).orElseThrow();
        if (best.legs().stream().anyMatch(PathLeg::belowMin)) {
            return blocked(in, PlanBlockReason.BELOW_MIN);
        }
        BigDecimal cost = best.costPct();
        FeeTestLevel level = feeTest.level(cost);
        List<PlanDraft.StepDraft> steps = new ArrayList<>(transitSteps);
        for (int i = 0; i < best.legs().size(); i++) {
            PathLeg leg = best.legs().get(i);
            steps.add(step(steps.size() + 1, in.assetSymbol(), leg, leg.sz().multiply(leg.refPrice()), best.estimation(), ctx));
        }
        String extra = best.legs().size() > 1 ? "Intermédiaire exposé : " + ctx.byId.get(best.legs().getFirst().instId()).quote()
                + " détenu entre les deux jambes (risque de prix)" : null;
        if (!transitNotes.isEmpty()) {
            extra = (extra == null ? "" : extra + " ; ") + String.join(" ; ", transitNotes);
        }
        log.info("Plan d'ordres vente {} : {} coût={}% Fee Test={}", in.assetSymbol(),
                best.legs().stream().map(PathLeg::instId).collect(Collectors.joining(">")), cost, level);
        return new PlanDraft.AssetDraft(in.assetSymbol(), in.priority(), in.walletId(), in.action(), AssetPlanOutcome.OK, null,
                cost, level, warnings(level, cost, best.estimation(), extra), steps);
    }

    /** Une route de vente : jambes dans l'ordre et estimation la moins fiable. */
    private record SellRoute(List<PathLeg> legs, PathEstimation estimation) {
        BigDecimal costPct() {
            return legs.stream().map(PathLeg::costPct).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * Vente {@code actif -> intermédiaire -> stable} (l'intermédiaire n'est jamais conservé) : jambe 1 {@code SELL actif-I} sur la
     * quantité, jambe 2 {@code SELL I-stable} dimensionnée sur le reçu net de la jambe 1 (⌊⌋ au lot).
     */
    private Optional<SellRoute> sellViaIntermediary(AssetInput in, WalletContext ctx, BigDecimal qty, BigDecimal notionalUsd,
                                                    String intermediary) {
        InstrumentInfo first = ctx.byId.get(in.assetSymbol() + "-" + intermediary);
        Optional<InstrumentInfo> second = ctx.instruments.stream()
                .filter(i -> i.base().equals(intermediary) && ctx.stables.contains(i.quote()) && !forbiddenNodes.contains(i.quote()))
                .min(Comparator.comparing(InstrumentInfo::instId));
        Optional<BigDecimal> intermediaryUsd = ctx.market.usdPrice(intermediary);
        if (first == null || second.isEmpty() || intermediaryUsd.isEmpty()) {
            return Optional.empty();
        }
        Optional<LegMarket> market1 = ctx.market.market(first, LegSide.SELL, notionalUsd.divide(intermediaryUsd.get(), AMOUNT_SCALE,
                RoundingMode.HALF_UP));
        if (market1.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal sz1 = PathFinder.roundToStep(qty, first.lotSz(), RoundingMode.DOWN);
        PathLeg leg1 = PathFinder.legOfSize(first, LegSide.SELL, market1.get(), sz1);
        BigDecimal net1 = sz1.multiply(market1.get().bid())
                .multiply(BigDecimal.ONE.subtract(market1.get().feePct().divide(HUNDRED, AMOUNT_SCALE, RoundingMode.HALF_UP)));
        BigDecimal sz2 = PathFinder.roundToStep(net1, second.get().lotSz(), RoundingMode.DOWN);
        Optional<LegMarket> market2 = ctx.market.market(second.get(), LegSide.SELL, net1.multiply(intermediaryUsd.get()));
        if (market2.isEmpty()) {
            return Optional.empty();
        }
        PathLeg leg2 = PathFinder.legOfSize(second.get(), LegSide.SELL, market2.get(), sz2);
        PathEstimation estimation = market1.get().estimation() == PathEstimation.TICKER || market2.get().estimation() == PathEstimation.TICKER
                ? PathEstimation.TICKER : PathEstimation.BOOK;
        return Optional.of(new SellRoute(List.of(leg1, leg2), estimation));
    }

    // ------------------------------------------------------------------ communs

    private PlanDraft.StepDraft step(int rank, String asset, PathLeg leg, BigDecimal quoteAmount, PathEstimation estimation,
                                     WalletContext ctx) {
        InstrumentInfo instrument = ctx.byId.get(leg.instId());
        StepSide side = leg.side() == LegSide.BUY ? StepSide.BUY : StepSide.SELL;
        return new PlanDraft.StepDraft(rank, asset, leg.instId(), side, leg.sz(), capPrice(leg, instrument), quoteAmount,
                leg.feePct(), leg.spreadPct(), leg.slippagePct(), estimation);
    }

    /** Prix plafond : prix de référence ± tolérance de slippage, arrondi au tick (BUY vers le haut, SELL vers le bas). */
    private BigDecimal capPrice(PathLeg leg, InstrumentInfo instrument) {
        BigDecimal tolerance = slippageTolerancePct.divide(HUNDRED, AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (leg.side() == LegSide.BUY) {
            return PathFinder.roundToStep(leg.refPrice().multiply(BigDecimal.ONE.add(tolerance)), instrument.tickSz(), RoundingMode.UP);
        }
        return PathFinder.roundToStep(leg.refPrice().multiply(BigDecimal.ONE.subtract(tolerance)), instrument.tickSz(),
                RoundingMode.DOWN);
    }

    private String warnings(FeeTestLevel level, BigDecimal costPct, PathEstimation estimation, String extra) {
        List<String> messages = new ArrayList<>();
        if (level == FeeTestLevel.WARNING) {
            messages.add("Coût du chemin " + pct(costPct) + " % au-dessus du seuil d'alerte (" + pct(feeTest.warnPct()) + " %)");
        } else if (level == FeeTestLevel.RED) {
            messages.add("Coût du chemin " + pct(costPct) + " % au-dessus du seuil rouge (" + pct(feeTest.redPct()) + " %)");
        }
        if (estimation == PathEstimation.TICKER) {
            messages.add("Estimation sur le meilleur bid/ask (carnet indisponible) : slippage non chiffré");
        }
        if (extra != null) {
            messages.add(extra);
        }
        return messages.isEmpty() ? null : String.join(" ; ", messages);
    }

    private static PlanDraft.AssetDraft blocked(AssetInput in, PlanBlockReason reason) {
        return draft(in, AssetPlanOutcome.BLOCKED, reason, null, null, null, List.of());
    }

    private static PlanDraft.AssetDraft draft(AssetInput in, AssetPlanOutcome outcome, PlanBlockReason reason, BigDecimal cost,
                                              FeeTestLevel level, String warning, List<PlanDraft.StepDraft> steps) {
        return new PlanDraft.AssetDraft(in.assetSymbol(), in.priority(), in.walletId(), in.action(), outcome, reason, cost,
                level, warning, steps);
    }

    /**
     * Statut du plan : {@code DISABLED} si tous les actifs sont désactivés ; {@code PLANNED} dès qu'un actif a des étapes
     * (ou s'il n'y a rien à faire) ; {@code BLOCKED} si des actions voulues n'ont donné aucune étape. Coût et niveau du plan :
     * le pire parmi les actifs exécutables.
     */
    private PlanDraft summarize(List<PlanDraft.AssetDraft> drafts) {
        List<PlanDraft.AssetDraft> executable = drafts.stream().filter(d -> d.outcome() == AssetPlanOutcome.OK).toList();
        List<PlanDraft.AssetDraft> blocked = drafts.stream().filter(d -> d.outcome() == AssetPlanOutcome.BLOCKED).toList();
        PlanStatus status;
        if (!drafts.isEmpty() && drafts.stream().allMatch(d -> d.outcome() == AssetPlanOutcome.DISABLED)) {
            status = PlanStatus.DISABLED;
        } else if (!executable.isEmpty() || blocked.isEmpty()) {
            status = PlanStatus.PLANNED;
        } else {
            status = PlanStatus.BLOCKED;
        }
        BigDecimal total = executable.stream().map(PlanDraft.AssetDraft::costPct).max(Comparator.naturalOrder()).orElse(null);
        FeeTestLevel level = executable.stream().map(PlanDraft.AssetDraft::feeTestLevel).max(Comparator.naturalOrder()).orElse(null);
        String reasons = blocked.isEmpty() ? null : blocked.stream().map(d -> d.assetSymbol() + ":" + d.blockReason())
                .collect(Collectors.joining(";"));
        return new PlanDraft(drafts, status, total, level, reasons);
    }

    private static String pct(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Hash SHA-256 (hex) des entrées du plan, indépendant des données de marché : sert l'idempotence. */
    public static String inputsHash(List<AssetInput> inputs) {
        StringBuilder canonical = new StringBuilder();
        inputs.stream().sorted(INPUT_ORDER).forEach(i -> {
            canonical.append(i.assetSymbol()).append('|').append(i.priority()).append('|').append(i.executionEnabled()).append('|')
                    .append(i.walletId()).append('|').append(i.status()).append('|').append(i.action()).append('|')
                    .append(plain(i.amountUsd())).append('|').append(plain(i.quantity())).append('|').append(plain(i.carryInUsd())).append('|');
            new TreeMap<>(i.cashByMember()).forEach((member, value) -> canonical.append(member).append('=').append(plain(value)).append(','));
            canonical.append('|');
            new TreeMap<>(i.transitStock()).forEach((currency, value) -> canonical.append(currency).append('=').append(plain(value)).append(','));
            canonical.append(';');
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
