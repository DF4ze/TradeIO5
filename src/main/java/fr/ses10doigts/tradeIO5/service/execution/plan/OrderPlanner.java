package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
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
 *   <li>Par wallet, un <b>bilan virtuel</b> par membre du groupe USD (initialisé du cash du snapshot de la passe) est
 *       parcouru par {@code priority} croissante, comme le {@code CashLedger} du bench. Les ventes ne le créditent pas.</li>
 *   <li>Achat : pour chaque cotation terminale {@code T} (paire directe {@code A-T}), si le solde {@code T} couvre le
 *       montant => 1 étape ; sinon une jambe pont ({@code S -> T}) pour le seul <b>manque</b> + marge de frais. Le
 *       candidat de coût brut minimal est retenu (ex æquo : moins d'étapes).</li>
 *   <li>Vente : cotation USDT forcée quand la paire existe, sinon cotation naturelle la moins chère + avertissement.</li>
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

    /** Un candidat d'achat : cotation terminale, devis de la jambe d'achat, pont éventuel et son montant. */
    private record BuyCandidate(String quote, PathQuote main, PathQuote bridge, BigDecimal bridgeAmount, BigDecimal totalCostPct) {
        int stepCount() {
            return bridge == null ? 1 : 2;
        }
    }

    public PlanDraft plan(List<AssetInput> inputs) {
        List<AssetInput> ordered = inputs.stream()
                .sorted(Comparator.comparingInt(AssetInput::priority).thenComparing(AssetInput::assetSymbol)).toList();
        Map<Long, WalletContext> contexts = new HashMap<>();
        Map<Long, Map<String, BigDecimal>> pools = new HashMap<>();
        List<PlanDraft.AssetDraft> drafts = new ArrayList<>();
        for (AssetInput input : ordered) {
            drafts.add(planAsset(input, contexts, pools));
        }
        return summarize(drafts);
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
        ExchangeLegMarketSource market = new ExchangeLegMarketSource(in.provider(), in.credential(), stables,
                feeProviders.get(in.provider()), bookClients.get(in.provider()));
        return new WalletContext(instruments, stables, market, cash);
    }

    // ------------------------------------------------------------------ achat

    private PlanDraft.AssetDraft planBuy(AssetInput in, WalletContext ctx) {
        BigDecimal amount = in.amountUsd();
        if (amount == null || amount.signum() <= 0) {
            return draft(in, AssetPlanOutcome.NO_ACTION, null, null, null, null, List.of());
        }
        List<BuyCandidate> viable = new ArrayList<>();
        PlanBlockReason failure = null;
        for (String quote : ctx.stables.stream().sorted().toList()) {
            PathQuote main = pathFinder.find(new PathFinder.Request(ctx.instruments, forbiddenNodes, Map.of(quote, amount),
                    in.assetSymbol(), amount, 1, quote), ctx.market);
            if (main.status() == PathStatus.NO_PATH) {
                continue;
            }
            BigDecimal held = ctx.held(quote);
            PathQuote bridge = null;
            BigDecimal bridgeAmount = null;
            if (held.compareTo(amount) < 0) {
                bridgeAmount = amount.subtract(held).multiply(BigDecimal.ONE
                        .add(ExecutionDefaults.BRIDGE_FEE_MARGIN_PCT.divide(HUNDRED, AMOUNT_SCALE, RoundingMode.HALF_UP)));
                Map<String, BigDecimal> others = new HashMap<>(ctx.virtual);
                others.remove(quote);
                bridge = pathFinder.find(new PathFinder.Request(ctx.instruments, forbiddenNodes, others, quote, bridgeAmount, 1,
                        null), ctx.market);
                if (bridge.status() == PathStatus.NO_PATH) {
                    failure = worst(failure, others.values().stream().anyMatch(b -> b.signum() > 0)
                            ? PlanBlockReason.NO_PATH : PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES);
                    continue;
                }
                if (bridge.underfunded()) {
                    failure = worst(failure, PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES);
                    continue;
                }
            }
            if (main.status() == PathStatus.BELOW_MIN || (bridge != null && bridge.status() == PathStatus.BELOW_MIN)) {
                failure = worst(failure, PlanBlockReason.BELOW_MIN);
                continue;
            }
            BigDecimal total = main.totalCostPct().add(bridge == null ? BigDecimal.ZERO : bridge.totalCostPct());
            viable.add(new BuyCandidate(quote, main, bridge, bridgeAmount, total));
        }

        Optional<BuyCandidate> best = viable.stream().min(Comparator.comparing(BuyCandidate::totalCostPct)
                .thenComparingInt(BuyCandidate::stepCount).thenComparing(BuyCandidate::quote));
        if (best.isEmpty()) {
            PlanBlockReason reason = failure != null ? failure : noStablePair(in.assetSymbol(), ctx)
                    ? PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT : PlanBlockReason.NO_PATH;
            return blocked(in, reason);
        }
        BuyCandidate c = best.get();
        FeeTestLevel level = feeTest.level(c.totalCostPct());

        List<PlanDraft.StepDraft> steps = new ArrayList<>();
        if (c.bridge() != null) {
            steps.add(step(steps.size() + 1, in.assetSymbol(), c.bridge().legs().getFirst(), c.bridgeAmount(),
                    c.bridge().estimation(), ctx));
        }
        steps.add(step(steps.size() + 1, in.assetSymbol(), c.main().legs().getFirst(), amount, c.main().estimation(), ctx));
        if (c.bridge() != null) {
            ctx.spend(c.bridge().source(), c.bridgeAmount());
            ctx.spend(c.quote(), ctx.held(c.quote())); // le reliquat éventuel du pont n'est pas compté (prudence)
        } else {
            ctx.spend(c.quote(), amount);
        }
        PathEstimation estimation = steps.stream().anyMatch(s -> s.estimation() == PathEstimation.TICKER)
                ? PathEstimation.TICKER : PathEstimation.BOOK;
        String warning = warnings(level, c.totalCostPct(), estimation, null);
        log.info("Plan d'ordres achat {} : {} étape(s) via {} coût={}% Fee Test={}", in.assetSymbol(), steps.size(), c.quote(),
                c.totalCostPct(), level);
        return new PlanDraft.AssetDraft(in.assetSymbol(), in.priority(), in.walletId(), in.action(), AssetPlanOutcome.OK, null,
                c.totalCostPct(), level, warning, steps);
    }

    private static PlanBlockReason worst(PlanBlockReason current, PlanBlockReason candidate) {
        if (current == null) {
            return candidate;
        }
        return rank(candidate) > rank(current) ? candidate : current;
    }

    private static int rank(PlanBlockReason reason) {
        return switch (reason) {
            case INSUFFICIENT_FUNDS_AFTER_FEES -> 3;
            case BELOW_MIN -> 2;
            default -> 1;
        };
    }

    /** Vrai si l'actif n'a aucune paire cotée en stable (pas de chemin possible sans monnaie fiat). */
    private boolean noStablePair(String asset, WalletContext ctx) {
        return ctx.instruments.stream().noneMatch(i -> i.base().equals(asset) && ctx.stables.contains(i.quote())
                && !forbiddenNodes.contains(i.quote()));
    }

    // ------------------------------------------------------------------ vente

    private PlanDraft.AssetDraft planSell(AssetInput in, WalletContext ctx) {
        BigDecimal qty = in.quantity();
        if (qty == null || qty.signum() <= 0) {
            return draft(in, AssetPlanOutcome.NO_ACTION, null, null, null, null, List.of());
        }
        List<InstrumentInfo> pairs = ctx.instruments.stream()
                .filter(i -> i.base().equals(in.assetSymbol()) && ctx.stables.contains(i.quote())
                        && !forbiddenNodes.contains(i.quote()))
                .sorted(Comparator.comparing(InstrumentInfo::instId)).toList();
        if (pairs.isEmpty()) {
            return blocked(in, PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT);
        }
        Optional<InstrumentInfo> forced = pairs.stream().filter(i -> i.quote().equals(ExecutionDefaults.FORCED_SELL_QUOTE)).findFirst();
        BigDecimal notional = in.amountUsd() != null && in.amountUsd().signum() > 0 ? in.amountUsd() : BigDecimal.ONE;

        PathLeg best = null;
        LegMarket bestMarket = null;
        for (InstrumentInfo pair : forced.map(List::of).orElse(pairs)) {
            Optional<LegMarket> market = ctx.market.market(pair, LegSide.SELL, notional);
            if (market.isEmpty()) {
                continue;
            }
            BigDecimal sz = PathFinder.roundToStep(qty, pair.lotSz(), RoundingMode.DOWN);
            PathLeg leg = PathFinder.legOfSize(pair, LegSide.SELL, market.get(), sz);
            if (best == null || leg.costPct().compareTo(best.costPct()) < 0) {
                best = leg;
                bestMarket = market.get();
            }
        }
        if (best == null) {
            return blocked(in, PlanBlockReason.NO_PATH);
        }
        if (best.belowMin()) {
            return blocked(in, PlanBlockReason.BELOW_MIN);
        }
        FeeTestLevel level = feeTest.level(best.costPct());
        InstrumentInfo instrument = ctx.byId.get(best.instId());
        String naturalQuote = forced.isEmpty() ? "pas de paire " + in.assetSymbol() + "-" + ExecutionDefaults.FORCED_SELL_QUOTE
                + " : cotation " + instrument.quote() : null;
        PlanDraft.StepDraft step = step(1, in.assetSymbol(), best, best.sz().multiply(best.refPrice()), bestMarket.estimation(), ctx);
        log.info("Plan d'ordres vente {} : {} coût={}% Fee Test={}", in.assetSymbol(), best.instId(), best.costPct(), level);
        return new PlanDraft.AssetDraft(in.assetSymbol(), in.priority(), in.walletId(), in.action(), AssetPlanOutcome.OK, null,
                best.costPct(), level, warnings(level, best.costPct(), bestMarket.estimation(), naturalQuote), List.of(step));
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
        inputs.stream().sorted(Comparator.comparingInt(AssetInput::priority).thenComparing(AssetInput::assetSymbol)).forEach(i -> {
            canonical.append(i.assetSymbol()).append('|').append(i.priority()).append('|').append(i.executionEnabled()).append('|')
                    .append(i.walletId()).append('|').append(i.status()).append('|').append(i.action()).append('|')
                    .append(plain(i.amountUsd())).append('|').append(plain(i.quantity())).append('|');
            new TreeMap<>(i.cashByMember()).forEach((member, value) -> canonical.append(member).append('=').append(plain(value)).append(','));
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
