package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.TransitStock;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderStepRepository;
import fr.ses10doigts.tradeIO5.repository.execution.TransitStockRepository;
import fr.ses10doigts.tradeIO5.service.execution.plan.ClOrdIds;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.ExecutionBlockedException;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.ExecutionGuard;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialResolver;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static fr.ses10doigts.tradeIO5.service.execution.run.OrderEventLog.payload;
import static fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults.CASH_STABLE;

/**
 * Exécuteur d'un plan d'ordres : le SEUL appelant du {@code SpotOrderPort} (avec {@link StepRunner}). Déclenchement
 * explicite uniquement (job désactivé par défaut, endpoint admin) ; jamais par le bench ni par le job de plan.
 * <p>
 * Vérifications, dans l'ordre : mode global {@code LIVE}, port présent, kill switch, statut / expiration du plan, puis par
 * actif : binding existant et lié au même wallet, {@code executionEnabled}, {@link ExecutionGuard}, credential TRADE, double
 * validation du 1ᵉʳ ordre, plafonds (ordre, jour, absolus). Ensuite, étape par étape : relecture du kill switch,
 * re-devis (carnet frais) et contrôle de dérive du coût, envoi, état, remplissages. Une étape n'utilise que les quantités
 * réellement reçues de la précédente. Échec partiel : le stock reste (aucun ordre compensatoire), plan {@code PARTIAL}.
 */
@Slf4j
@Service
public class OrderExecutor {

    private static final String ALL = "*";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final EnumSet<StepStatus> SENT = EnumSet.of(StepStatus.SUBMITTED, StepStatus.FILLED, StepStatus.PARTIAL, StepStatus.UNKNOWN);

    /** Récapitulatif d'un déclenchement par jour (job). */
    public record RunSummary(LocalDate day, int executed, int blocked, int skipped, int errors) {
    }

    private record StepView(Long id, int rank, String asset, Long walletId, String instId, StepSide side, BigDecimal sz, BigDecimal px,
                            BigDecimal quoteAmount, String clOrdId, BigDecimal feePct, BigDecimal plannedCostPct, StepStatus status,
                            BigDecimal sentSz, BigDecimal mid, BigDecimal filledSz, BigDecimal avgPx, BigDecimal fee, String feeCcy,
                            BigDecimal receivedAmount, String receivedCcy) {
        /** Étape cotée en stable de cash : elle porte le notionnel USD des plafonds (les jambes cotées en intermédiaire, non). */
        boolean cash() {
            return instId.endsWith("-" + CASH_STABLE);
        }
    }

    private record AssetView(String symbol, Long walletId, BigDecimal costPct) {
        /** Clé de blocage et de journal : un même actif peut être exécuté sur plusieurs wallets. */
        String key() {
            return keyOf(symbol, walletId);
        }

        /** Étape de cette chaîne (actif + wallet ; une étape sans wallet, d'avant la clé (actif, wallet), suit l'actif). */
        boolean owns(StepView step) {
            return symbol.equals(step.asset()) && (step.walletId() == null || step.walletId().equals(walletId));
        }
    }

    private static String keyOf(String symbol, Long walletId) {
        return walletId == null ? symbol : symbol + "@" + walletId;
    }

    private record PlanView(Long id, User user, LocalDate day, Instant expiresAt, PlanStatus status, List<AssetView> assets,
                            List<StepView> steps) {
    }

    private record BindingView(boolean enabled, boolean executable, boolean firstLiveValidated, Long walletId, boolean walletUsable,
                               WebProviderCode provider, ApiCredential readCredential, WebProvider webProvider,
                               BigDecimal baseAmount, BigDecimal userBaseSum) {
    }

    /** État mutable d'un déclenchement. */
    private static final class Run {
        private final Map<String, ExecutionBlockReason> blocks = new LinkedHashMap<>();
        /** Première raison de non-envoi par actif (coût rouge, dérive, minimum…) : remontée à l'affichage du plan. */
        private final Map<String, String> skips = new LinkedHashMap<>();
        private int sent;
    }

    private final ExecutionSettings settings;
    private final ExecutionControlService control;
    private final SpotOrderRouter router;
    private final RainbowLiveOrderPlanRepository plans;
    private final RainbowLiveOrderStepRepository steps;
    private final RainbowLiveBindingRepository bindings;
    private final RainbowLiveRunRepository runs;
    private final WalletRepository wallets;
    private final UserRepository users;
    private final TradeCredentialResolver credentials;
    private final InstrumentCatalog catalog;
    private final StepRequoter requoter;
    private final StepRunner stepRunner;
    private final OrderEventLog events;
    private final FeeTest feeTest;
    private final DomainClock clock;
    private final TransitStockRepository transit;
    private final Duration retryDelay;
    private final TransactionTemplate tx;

    @Autowired
    public OrderExecutor(ExecutionSettings settings, ExecutionControlService control, SpotOrderRouter router,
                         RainbowLiveOrderPlanRepository plans, RainbowLiveOrderStepRepository steps,
                         RainbowLiveBindingRepository bindings, RainbowLiveRunRepository runs, WalletRepository wallets,
                         UserRepository users, TradeCredentialResolver credentials, InstrumentCatalog catalog,
                         StepRequoter requoter, StepRunner stepRunner, OrderEventLog events, FeeTest feeTest, DomainClock clock,
                         TransitStockRepository transit, PlatformTransactionManager transactionManager) {
        this(settings, control, router, plans, steps, bindings, runs, wallets, users, credentials, catalog, requoter, stepRunner, events,
                feeTest, clock, transit, transactionManager, ExecutionDefaults.LEG2_RETRY_DELAY);
    }

    OrderExecutor(ExecutionSettings settings, ExecutionControlService control, SpotOrderRouter router,
                  RainbowLiveOrderPlanRepository plans, RainbowLiveOrderStepRepository steps,
                  RainbowLiveBindingRepository bindings, RainbowLiveRunRepository runs, WalletRepository wallets,
                  UserRepository users, TradeCredentialResolver credentials, InstrumentCatalog catalog,
                  StepRequoter requoter, StepRunner stepRunner, OrderEventLog events, FeeTest feeTest, DomainClock clock,
                  TransitStockRepository transit, PlatformTransactionManager transactionManager, Duration retryDelay) {
        this.transit = transit;
        this.retryDelay = retryDelay;
        this.settings = settings;
        this.control = control;
        this.router = router;
        this.plans = plans;
        this.steps = steps;
        this.bindings = bindings;
        this.runs = runs;
        this.wallets = wallets;
        this.users = users;
        this.credentials = credentials;
        this.catalog = catalog;
        this.requoter = requoter;
        this.stepRunner = stepRunner;
        this.events = events;
        this.feeTest = feeTest;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ déclenchements

    /** Exécute les plans courants {@code PLANNED} de tous les utilisateurs actifs pour le jour. */
    public RunSummary executeAll(LocalDate day) {
        if (!settings.liveExecution()) {
            log.info("Exécution : mode {} => aucun ordre (jour {})", settings.getMode(), day);
            return new RunSummary(day, 0, 0, 0, 0);
        }
        int executed = 0;
        int blocked = 0;
        int skipped = 0;
        int errors = 0;
        for (User user : users.findByEnabledTrueAndArchivedAtIsNull()) {
            Optional<RainbowLiveOrderPlan> plan = plans.findFirstByUserAndDayOrderByRevisionDesc(user, day);
            if (plan.isEmpty() || plan.get().getStatus() != PlanStatus.PLANNED) {
                skipped++;
                continue;
            }
            try {
                ExecutionResult result = execute(plan.get().getId());
                if (result.sent() > 0) {
                    executed++;
                } else {
                    blocked++;
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("Exécution : échec pour user={} plan={}", user.getId(), plan.get().getId(), e);
            }
        }
        RunSummary summary = new RunSummary(day, executed, blocked, skipped, errors);
        log.info("Exécution : {}", summary);
        return summary;
    }

    /** Exchanges disposant d'un port d'ordres actif (vide hors LIVE déverrouillé) : affichage de la page Admin. */
    public List<WebProviderCode> orderProviders() {
        return router.providers();
    }

    /**
     * Exécute un plan {@code PLANNED}. Un plan {@code EXECUTING} n'est jamais renvoyé : il est seulement réconcilié.
     *
     * @throws IllegalArgumentException plan inconnu
     */
    public ExecutionResult execute(Long planId) {
        PlanView plan = loadPlan(planId);
        Optional<ExecutionBlockReason> global = globalBlock(plan);
        if (global.isPresent()) {
            return refuse(plan, global.get());
        }
        if (plan.status() == PlanStatus.EXECUTING) {
            log.info("Plan {} déjà en exécution : réconciliation sans renvoi", planId);
            return reconcile(planId);
        }
        if (plan.steps().isEmpty()) {
            log.debug("Plan {} sans étape : rien à exécuter", planId);
            return new ExecutionResult(planId, plan.status(), Map.of(), 0);
        }
        Integer claimed = tx.execute(status -> plans.transition(planId, PlanStatus.PLANNED, PlanStatus.EXECUTING, clock.now()));
        if (claimed == null || claimed == 0) {
            return refuse(plan, ExecutionBlockReason.PLAN_NOT_EXECUTABLE);
        }
        events.record(planId, null, null, OrderEventType.PLAN_STARTED, payload("steps", plan.steps().size()));
        log.info("Exécution du plan {} (user={} jour {}) : {} étape(s)", planId, plan.user().getId(), plan.day(), plan.steps().size());

        Run run = new Run();
        for (AssetView asset : plan.assets()) {
            List<StepView> assetSteps = plan.steps().stream().filter(asset::owns)
                    .sorted(Comparator.comparingInt(StepView::rank)).toList();
            if (assetSteps.isEmpty()) {
                continue;
            }
            if (!liveAllowed()) {
                block(plan, run, asset.key(), ExecutionBlockReason.KILL_SWITCH);
                continue;
            }
            try {
                runAsset(plan, asset, assetSteps, run);
            } catch (RuntimeException e) {
                log.error("Exécution du plan {} : erreur inattendue sur {}", planId, asset.key(), e);
                events.record(planId, null, null, OrderEventType.ALERT, payload("asset", asset.key(), "reason", "UNEXPECTED_ERROR",
                        "error", e.getClass().getSimpleName()));
            }
        }
        return finish(plan.id(), run);
    }

    /**
     * Relit par {@code clOrdId} toute étape {@code SUBMITTED / UNKNOWN} du plan et clôt le plan. <b>N'envoie jamais d'ordre</b> ;
     * sans effet hors mode LIVE.
     */
    public ExecutionResult reconcile(Long planId) {
        PlanView plan = loadPlan(planId);
        if (!settings.liveExecution()) {
            return ExecutionResult.blockedGlobally(planId, plan.status(), ExecutionBlockReason.MODE_NOT_LIVE);
        }
        Run run = new Run();
        for (StepView step : plan.steps()) {
            if (!step.status().inFlight()) {
                continue;
            }
            AssetView asset = plan.assets().stream().filter(a -> a.owns(step)).findFirst().orElse(null);
            String key = asset == null ? keyOf(step.asset(), step.walletId()) : asset.key();
            Wallet wallet = asset == null ? null : wallets.findById(asset.walletId()).orElse(null);
            Optional<ApiCredential> trade = wallet == null || wallet.getWebProviderCode() == null ? Optional.empty()
                    : credentials.resolve(plan.user(), wallet.getWebProviderCode());
            if (wallet != null && wallet.getWebProviderCode() != null && router.forProvider(wallet.getWebProviderCode()).isEmpty()) {
                log.warn("Réconciliation du plan {} : aucun client d'ordres pour {} ({})", planId, wallet.getWebProviderCode(), step.clOrdId());
                run.blocks.put(key, ExecutionBlockReason.PORT_UNAVAILABLE);
                continue;
            }
            if (trade.isEmpty()) {
                log.warn("Réconciliation du plan {} : credential TRADE indisponible pour {}", planId, step.clOrdId());
                run.blocks.put(key, ExecutionBlockReason.CREDENTIAL_INVALID);
                continue;
            }
            InstrumentInfo instrument = instrumentsOf(wallet.getWebProvider()).get(step.instId());
            if (instrument == null) {
                run.blocks.put(key, ExecutionBlockReason.PORT_UNAVAILABLE);
                continue;
            }
            stepRunner.reconcile(new StepRunner.StepContext(planId, wallet.getId(), step.id(), step.clOrdId(), step.instId(),
                    step.side(), step.sentSz() != null ? step.sentSz() : step.sz(), step.px(), instrument, trade.get(), step.mid(),
                    wallet.getWebProviderCode()));
        }
        return finish(planId, run);
    }

    // ------------------------------------------------------------------ gardes

    private Optional<ExecutionBlockReason> globalBlock(PlanView plan) {
        if (!settings.liveExecution()) {
            return Optional.of(ExecutionBlockReason.MODE_NOT_LIVE);
        }
        if (router.providers().isEmpty()) {
            return Optional.of(ExecutionBlockReason.PORT_UNAVAILABLE);
        }
        if (control.current().isKillSwitch()) {
            return Optional.of(ExecutionBlockReason.KILL_SWITCH);
        }
        if (plan.status() != PlanStatus.PLANNED && plan.status() != PlanStatus.EXECUTING) {
            return Optional.of(ExecutionBlockReason.PLAN_NOT_EXECUTABLE);
        }
        if (plan.status() == PlanStatus.PLANNED && !clock.now().isBefore(plan.expiresAt())) {
            return Optional.of(ExecutionBlockReason.PLAN_EXPIRED);
        }
        return Optional.empty();
    }

    private boolean liveAllowed() {
        return settings.liveExecution() && !control.current().isKillSwitch();
    }

    private ExecutionResult refuse(PlanView plan, ExecutionBlockReason reason) {
        log.warn("Exécution du plan {} refusée : {}", plan.id(), reason);
        events.record(plan.id(), null, null, OrderEventType.BLOCKED, payload("reason", reason));
        return ExecutionResult.blockedGlobally(plan.id(), plan.status(), reason);
    }

    /** Aucun client d'ordres pour ce provider : seules ses étapes sont bloquées (les autres providers continuent). */
    private void blockNoPort(PlanView plan, Run run, String asset, WebProviderCode provider) {
        run.blocks.put(asset, ExecutionBlockReason.PORT_UNAVAILABLE);
        log.warn("Plan {} : exécution de {} bloquée (PORT_UNAVAILABLE : aucun client d'ordres pour {})", plan.id(), asset, provider);
        events.record(plan.id(), null, null, OrderEventType.BLOCKED,
                payload("asset", asset, "reason", ExecutionBlockReason.PORT_UNAVAILABLE, "provider", provider));
    }

    private void block(PlanView plan, Run run, String asset, ExecutionBlockReason reason) {
        run.blocks.put(asset, reason);
        log.warn("Plan {} : exécution de {} bloquée ({})", plan.id(), asset, reason);
        events.record(plan.id(), null, null, OrderEventType.BLOCKED, payload("asset", asset, "reason", reason));
    }

    // ------------------------------------------------------------------ un actif

    private void runAsset(PlanView plan, AssetView asset, List<StepView> assetSteps, Run run) {
        BindingView binding = loadBinding(plan, asset.symbol(), asset.walletId());
        if (binding == null || !asset.walletId().equals(binding.walletId()) || !binding.walletUsable()) {
            block(plan, run, asset.key(), ExecutionBlockReason.NO_BINDING);
            return;
        }
        if (!binding.enabled()) {
            block(plan, run, asset.key(), ExecutionBlockReason.BINDING_DISABLED);
            return;
        }
        if (!binding.executable()) {
            block(plan, run, asset.key(), ExecutionBlockReason.NOT_TRADABLE);
            return;
        }
        if (router.forProvider(binding.provider()).isEmpty()) {
            blockNoPort(plan, run, asset.key(), binding.provider());
            return;
        }
        Optional<ApiCredential> trade = credentials.resolve(plan.user(), binding.provider());
        if (trade.isEmpty()) {
            block(plan, run, asset.key(), ExecutionBlockReason.CREDENTIAL_INVALID);
            return;
        }
        if (!binding.firstLiveValidated()) {
            block(plan, run, asset.key(), ExecutionBlockReason.FIRST_LIVE_NOT_VALIDATED);
            return;
        }
        Optional<ExecutionBlockReason> cap = capBlock(plan, asset, assetSteps, binding);
        if (cap.isPresent()) {
            block(plan, run, asset.key(), cap.get());
            return;
        }
        Map<String, InstrumentInfo> instruments;
        try {
            instruments = instrumentsOf(binding.webProvider());
        } catch (RuntimeException e) {
            block(plan, run, asset.key(), ExecutionBlockReason.PORT_UNAVAILABLE);
            return;
        }
        runChain(plan, asset, assetSteps, binding, trade.get(), instruments, run);
    }

    /** Plafonds : par ordre (× baseAmount du preset, puis absolu) et par jour / utilisateur (× somme des baseAmount, puis absolu). */
    private Optional<ExecutionBlockReason> capBlock(PlanView plan, AssetView asset, List<StepView> assetSteps, BindingView binding) {
        if (binding.baseAmount() == null || binding.baseAmount().signum() <= 0) {
            return Optional.of(ExecutionBlockReason.NO_BASE_AMOUNT);
        }
        ExecutionControl ctl = control.current();
        BigDecimal orderCap = ctl.getMaxOrderMultiplier().multiply(binding.baseAmount());
        BigDecimal assetNotional = BigDecimal.ZERO;
        for (StepView step : assetSteps) {
            if (step.cash()) {
                if (step.quoteAmount().compareTo(ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD) > 0) {
                    return Optional.of(ExecutionBlockReason.CAP_ABSOLUTE_ORDER);
                }
                if (step.quoteAmount().compareTo(orderCap) > 0) {
                    return Optional.of(ExecutionBlockReason.CAP_ORDER);
                }
                assetNotional = assetNotional.add(step.quoteAmount());
            }
        }
        Instant dayStart = clock.now().truncatedTo(ChronoUnit.DAYS);
        BigDecimal daySpent = tx.execute(status -> steps.findSentSince(plan.user(), dayStart, SENT).stream()
                .filter(s -> s.getInstId().endsWith("-" + CASH_STABLE))
                .map(RainbowLiveOrderStep::getQuoteAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal dayTotal = (daySpent == null ? BigDecimal.ZERO : daySpent).add(assetNotional);
        if (dayTotal.compareTo(ExecutionDefaults.ABSOLUTE_MAX_DAY_USD) > 0) {
            return Optional.of(ExecutionBlockReason.CAP_ABSOLUTE_DAY);
        }
        if (dayTotal.compareTo(ctl.getMaxDayMultiplier().multiply(binding.userBaseSum())) > 0) {
            return Optional.of(ExecutionBlockReason.CAP_DAY);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ chaîne d'étapes d'un actif

    private void runChain(PlanView plan, AssetView asset, List<StepView> assetSteps, BindingView binding, ApiCredential trade,
                          Map<String, InstrumentInfo> instruments, Run run) {
        BigDecimal planCost = asset.costPct() != null ? asset.costPct()
                : assetSteps.stream().map(StepView::plannedCostPct).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Quantités reçues par la chaîne et pas encore dépensées (devise -> quantité) : la jambe suivante n'utilise que le net reçu.
        Map<String, BigDecimal> held = new HashMap<>();
        boolean stop = false;
        for (StepView step : assetSteps) {
            if (stop) {
                skip(plan, asset, run, step, "PREVIOUS_STEP_NOT_EXECUTED");
                continue;
            }
            if (!liveAllowed()) {
                skip(plan, asset, run, step, ExecutionBlockReason.KILL_SWITCH.name());
                run.blocks.put(asset.key(), ExecutionBlockReason.KILL_SWITCH);
                stop = true;
                continue;
            }
            InstrumentInfo instrument = instruments.get(step.instId());
            if (instrument == null) {
                skip(plan, asset, run, step, "INSTRUMENT_UNKNOWN");
                stop = true;
                continue;
            }
            String spendCurrency = spendCurrency(step.side(), instrument);
            BigDecimal feed = held.get(spendCurrency);
            boolean leg2 = feed != null;
            if (leg2 && feed.signum() <= 0) {
                skip(plan, asset, run, step, "LEG_RECEIVED_UNUSABLE");
                stop = true;
                continue;
            }
            int maxAttempts = leg2 ? ExecutionDefaults.LEG2_MAX_ATTEMPTS : 1;
            StepRunner.StepResult result = null;
            String notSent = null;
            int sends = 0;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (attempt > 1) {
                    pause();
                    if (!liveAllowed()) {
                        notSent = ExecutionBlockReason.KILL_SWITCH.name();
                        run.blocks.put(asset.key(), ExecutionBlockReason.KILL_SWITCH);
                        break;
                    }
                }
                String clOrdId = ClOrdIds.attempt(step.clOrdId(), sends + 1);
                if (sends > 0) {
                    stepRunner.restage(step.id(), clOrdId);
                }
                BigDecimal sz = sizeOf(step, instrument, feed);
                if (sz.signum() <= 0 || sz.compareTo(instrument.minSz()) < 0
                        || (instrument.costMin() != null && sz.multiply(step.px()).compareTo(instrument.costMin()) < 0)) {
                    notSent = "BELOW_MIN";
                    break;
                }
                StepRequoter.Requote quote;
                try {
                    quote = requoter.requote(binding.provider(), binding.readCredential(), instrument, step.side(), sz,
                            sz.multiply(step.px()));
                } catch (RuntimeException e) {
                    notSent = "REQUOTE_UNAVAILABLE";
                    continue;
                }
                BigDecimal others = assetSteps.stream().filter(s -> !s.id().equals(step.id())).map(StepView::plannedCostPct)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal projected = others.add(quote.costPct());
                events.record(plan.id(), step.id(), clOrdId, OrderEventType.STEP_REQUOTED,
                        payload("legCostPct", quote.costPct(), "projectedPct", projected, "planCostPct", planCost, "mid", quote.mid(),
                                "attempt", attempt));
                if (feeTest.level(projected) == FeeTestLevel.RED) {
                    notSent = "COST_RED";
                    break;
                }
                if (projected.compareTo(planCost.add(ExecutionDefaults.COST_DRIFT_POINTS)) > 0) {
                    notSent = "COST_DRIFT";
                    break;
                }
                if (!liveAllowed()) {
                    notSent = ExecutionBlockReason.KILL_SWITCH.name();
                    run.blocks.put(asset.key(), ExecutionBlockReason.KILL_SWITCH);
                    break;
                }
                notSent = null;
                result = stepRunner.send(new StepRunner.StepContext(plan.id(), binding.walletId(), step.id(), clOrdId, step.instId(),
                        step.side(), sz, step.px(), instrument, trade, quote.mid(), binding.provider()));
                sends++;
                run.sent++;
                if (result.status().executed() || result.status().inFlight()) {
                    break;
                }
                if (leg2 && attempt < maxAttempts) {
                    events.record(plan.id(), step.id(), clOrdId, OrderEventType.ALERT,
                            payload("reason", "LEG2_RETRY", "attempt", attempt, "status", result.status()));
                    log.warn("Plan {} : jambe 2 {} non exécutée (essai {}/{}), re-devis puis nouvel essai", plan.id(), step.instId(),
                            attempt, maxAttempts);
                }
            }
            if (result == null) {
                skip(plan, asset, run, step, notSent == null ? "NOT_SENT" : notSent);
                stop = true;
                continue;
            }
            if (result.status().executed()) {
                FillAccounting.Outcome outcome = result.outcome();
                if (feed != null) {
                    held.merge(spendCurrency, spent(step.side(), outcome, instrument).negate(), BigDecimal::add);
                } else {
                    consumeTransit(plan, binding.walletId(), asset.symbol(), step.side(), spendCurrency,
                            spent(step.side(), outcome, instrument));
                }
                held.merge(outcome.receivedCurrency(), outcome.received(), BigDecimal::add);
                if (result.status() != StepStatus.FILLED) {
                    stop = true;
                }
            } else {
                stop = true;
            }
        }
    }

    /**
     * Taille d'une étape : la taille planifiée, ramenée à ce que la jambe précédente a réellement reçu (net de frais) quand
     * l'étape en dépend ({@code feed} non nul), arrondie au lot.
     */
    private static BigDecimal sizeOf(StepView step, InstrumentInfo instrument, BigDecimal feed) {
        if (feed == null) {
            return step.sz();
        }
        BigDecimal affordable;
        if (step.side() == StepSide.BUY) {
            BigDecimal fee = BigDecimal.ONE.add(step.feePct().divide(HUNDRED, 18, RoundingMode.HALF_UP));
            affordable = feed.divide(step.px().multiply(fee), 18, RoundingMode.DOWN);
        } else {
            affordable = feed;
        }
        return step.sz().min(PathFinder.roundToStep(affordable, instrument.lotSz(), RoundingMode.DOWN));
    }

    /** Devise dépensée par une étape : la cotation en achat, la base en vente. */
    private static String spendCurrency(StepSide side, InstrumentInfo instrument) {
        return side == StepSide.BUY ? instrument.quote() : instrument.base();
    }

    /** Quantité de la devise dépensée par une exécution (frais inclus quand ils sont prélevés dans cette devise). */
    private static BigDecimal spent(StepSide side, FillAccounting.Outcome outcome, InstrumentInfo instrument) {
        if (outcome.filledSz() == null || outcome.filledSz().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (side == StepSide.SELL) {
            return outcome.filledSz();
        }
        BigDecimal notional = outcome.filledSz().multiply(outcome.avgPx());
        return outcome.feeCurrency() != null && outcome.feeCurrency().equalsIgnoreCase(instrument.quote()) && outcome.fee() != null
                ? notional.add(outcome.fee()) : notional;
    }

    /** Une étape qui dépense un stock en transit existant (et non la sortie d'une jambe de ce run) en diminue les lignes, du plus ancien au plus récent. */
    private void consumeTransit(PlanView plan, Long walletId, String asset, StepSide side, String currency, BigDecimal spent) {
        if (spent.signum() <= 0 || CASH_STABLE.equalsIgnoreCase(currency)) {
            return;
        }
        tx.executeWithoutResult(status -> {
            BigDecimal remaining = spent;
            List<TransitStock> stocks = transit.findByWalletIdAndTargetAssetAndResolvedAtIsNull(walletId, asset).stream()
                    .filter(s -> s.getCurrency().equalsIgnoreCase(currency)
                            && (s.getFinishSide() == null ? StepSide.BUY : s.getFinishSide()) == side)
                    .sorted(Comparator.comparing(TransitStock::getCreatedAt)).toList();
            for (TransitStock stock : stocks) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal used = stock.getQuantity().min(remaining);
                stock.setQuantity(stock.getQuantity().subtract(used));
                remaining = remaining.subtract(used);
                if (stock.getQuantity().signum() <= 0) {
                    stock.setResolvedAt(clock.now());
                }
                transit.save(stock);
            }
        });
        events.record(plan.id(), null, null, OrderEventType.ALERT,
                payload("reason", "TRANSIT_STOCK_CONSUMED", "currency", currency, "spent", spent, "asset", asset));
    }

    /**
     * Après un déclenchement ou une réconciliation : toute jambe 2 qui n'a pas été (entièrement) exécutée alors que la jambe
     * précédente a reçu l'intermédiaire laisse un stock en transit (aucun ordre compensatoire ; le plan suivant termine la
     * jambe 2 avant tout nouvel ordre). Idempotent par (plan, rang de l'étape d'origine, wallet, actif).
     */
    private void recordTransitStock(Long planId) {
        PlanView plan = loadPlan(planId);
        Map<String, InstrumentInfo> cache = new HashMap<>();
        for (AssetView asset : plan.assets()) {
            List<StepView> chain = plan.steps().stream().filter(asset::owns).sorted(Comparator.comparingInt(StepView::rank)).toList();
            for (int i = 1; i < chain.size(); i++) {
                StepView previous = chain.get(i - 1);
                StepView next = chain.get(i);
                if (!previous.status().executed() || previous.receivedAmount() == null || previous.receivedCcy() == null
                        || next.status().inFlight()) {
                    continue;
                }
                InstrumentInfo instrument = instrumentOfStep(plan, asset, next, cache);
                if (instrument == null || !previous.receivedCcy().equalsIgnoreCase(spendCurrency(next.side(), instrument))
                        || previous.receivedCcy().equalsIgnoreCase(CASH_STABLE) || previous.receivedCcy().equalsIgnoreCase(asset.symbol())) {
                    continue;
                }
                BigDecimal quantity = previous.receivedAmount();
                if (next.status() == StepStatus.FILLED) {
                    continue;
                }
                if (next.status() == StepStatus.PARTIAL) {
                    quantity = quantity.subtract(spentOf(next, instrument));
                }
                if (quantity.signum() <= 0 || transit.existsByOriginPlanIdAndOriginStepRankAndWalletIdAndTargetAsset(planId,
                        previous.rank(), asset.walletId(), asset.symbol())) {
                    continue;
                }
                BigDecimal stockQuantity = quantity;
                tx.executeWithoutResult(status -> transit.save(TransitStock.builder().userId(plan.user().getId()).walletId(asset.walletId())
                        .currency(previous.receivedCcy()).quantity(stockQuantity).targetAsset(asset.symbol()).finishSide(next.side())
                        .originPlanId(planId).originStepRank(previous.rank()).createdAt(clock.now()).build()));
                events.record(planId, next.id(), next.clOrdId(), OrderEventType.ALERT, payload("reason", "TRANSIT_STOCK",
                        "currency", previous.receivedCcy(), "quantity", stockQuantity, "asset", asset.symbol(), "finishSide", next.side()));
                log.warn("Plan {} : jambe 2 {} non exécutée, {} {} laissés en stock en transit (aucun ordre compensatoire)", planId,
                        next.instId(), stockQuantity.toPlainString(), previous.receivedCcy());
            }
        }
    }

    private InstrumentInfo instrumentOfStep(PlanView plan, AssetView asset, StepView step, Map<String, InstrumentInfo> cache) {
        if (cache.isEmpty()) {
            try {
                BindingView binding = loadBinding(plan, asset.symbol(), asset.walletId());
                if (binding == null || binding.webProvider() == null) {
                    return null;
                }
                cache.putAll(instrumentsOf(binding.webProvider()));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return cache.get(step.instId());
    }

    private static BigDecimal spentOf(StepView step, InstrumentInfo instrument) {
        if (step.filledSz() == null || step.filledSz().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return spent(step.side(), new FillAccounting.Outcome(step.status(), step.filledSz(), step.avgPx(), step.fee(), step.feeCcy(),
                step.receivedAmount(), step.receivedCcy()), instrument);
    }

    private void pause() {
        if (retryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void skip(PlanView plan, AssetView asset, Run run, StepView step, String reason) {
        if (!"PREVIOUS_STEP_NOT_EXECUTED".equals(reason)) {
            run.skips.putIfAbsent(asset.key(), reason);
        }
        log.info("Plan {} : étape {} non envoyée ({})", plan.id(), step.clOrdId(), reason);
        events.record(plan.id(), step.id(), step.clOrdId(), OrderEventType.STEP_SKIPPED, payload("reason", reason));
    }

    // ------------------------------------------------------------------ clôture

    /** Statut du plan d'après ses étapes ; relit la base (les étapes ont été mises à jour par le runner). */
    private ExecutionResult finish(Long planId, Run run) {
        recordTransitStock(planId);
        PlanStatus status = tx.execute(txStatus -> {
            RainbowLiveOrderPlan plan = plans.findById(planId).orElseThrow();
            List<StepStatus> statuses = plan.getSteps().stream().map(RainbowLiveOrderStep::getStatus).toList();
            PlanStatus next;
            if (statuses.stream().anyMatch(StepStatus::inFlight)) {
                next = PlanStatus.EXECUTING;
            } else if (!statuses.isEmpty() && statuses.stream().allMatch(s -> s == StepStatus.FILLED)) {
                next = PlanStatus.EXECUTED;
            } else if (statuses.stream().anyMatch(StepStatus::executed)) {
                next = PlanStatus.PARTIAL;
            } else if (statuses.stream().anyMatch(s -> s == StepStatus.REJECTED || s == StepStatus.CANCELED)) {
                next = PlanStatus.FAILED;
            } else if (run.blocks.values().stream().anyMatch(ExecutionBlockReason::permanent)) {
                next = PlanStatus.BLOCKED;
            } else {
                next = PlanStatus.PLANNED;
            }
            plan.setStatus(next);
            String reasons = Stream.concat(run.blocks.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()),
                    run.skips.entrySet().stream().filter(e -> !run.blocks.containsKey(e.getKey())).map(e -> e.getKey() + ":" + e.getValue()))
                    .collect(Collectors.joining(";"));
            plan.setExecutionBlockReason(reasons.isEmpty() ? null : reasons.length() > 120 ? reasons.substring(0, 120) : reasons);
            plan.setExecutionFinishedAt(next == PlanStatus.EXECUTING || next == PlanStatus.PLANNED ? null : clock.now());
            if (next == PlanStatus.PLANNED) {
                plan.setExecutionStartedAt(null);
            }
            plans.save(plan);
            return next;
        });
        events.record(planId, null, null, OrderEventType.PLAN_RESULT,
                payload("status", status, "sent", run.sent, "blocked", run.blocks.isEmpty() ? null : run.blocks.toString()));
        if (status == PlanStatus.PARTIAL || status == PlanStatus.FAILED) {
            events.record(planId, null, null, OrderEventType.ALERT, payload("reason", "PLAN_" + status));
            log.warn("Plan {} terminé {} : stock conservé, aucun ordre compensatoire", planId, status);
        } else {
            log.info("Plan {} : {} ({} ordre(s) envoyé(s))", planId, status, run.sent);
        }
        return new ExecutionResult(planId, status, Map.copyOf(run.blocks), run.sent);
    }

    // ------------------------------------------------------------------ lectures

    private PlanView loadPlan(Long planId) {
        PlanView view = tx.execute(status -> plans.findById(planId).map(plan -> new PlanView(plan.getId(), plan.getUser(), plan.getDay(),
                plan.getExpiresAt(), plan.getStatus(),
                plan.getAssets().stream().map(a -> new AssetView(a.getAssetSymbol(), a.getWalletId(), a.getCostPct())).toList(),
                plan.getSteps().stream().map(s -> new StepView(s.getId(), s.getRank(), s.getAssetSymbol(), s.getWalletId(), s.getInstId(), s.getSide(),
                        s.getSz(), s.getPx(), s.getQuoteAmount(), s.getClOrdId(), s.getFeePct(),
                        s.getFeePct().add(s.getSpreadPct()).add(s.getSlippagePct()), s.getStatus(), s.getSentSz(),
                        s.getQuoteMid(), s.getFilledSz(), s.getAvgFillPx(), s.getFeeAmount(), s.getFeeCurrency(), s.getReceivedAmount(),
                        s.getReceivedCurrency())).toList())).orElse(null));
        if (view == null) {
            throw new IllegalArgumentException("Plan inconnu : " + planId);
        }
        return view;
    }

    private BindingView loadBinding(PlanView plan, String asset, Long walletId) {
        return tx.execute(status -> {
            RainbowLiveBinding binding = bindings.findByUserAndAssetSymbolAndWallet_Id(plan.user(), asset, walletId).orElse(null);
            if (binding == null) {
                return null;
            }
            Wallet wallet = binding.getWallet();
            boolean executable;
            try {
                ExecutionGuard.requireExecutable(binding);
                executable = true;
            } catch (ExecutionBlockedException e) {
                executable = false;
            }
            BigDecimal userBaseSum = bindings.findByUserOrderByPriorityAscAssetSymbolAsc(plan.user()).stream()
                    .map(b -> baseAmount(b, plan.day())).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean walletUsable = wallet.isEnabled() && wallet.getSource() == WalletSource.EXCHANGE
                    && wallet.getCredential() != null && wallet.getCredential().isEnabled() && wallet.getWebProviderCode() != null
                    && wallet.getWebProvider() != null;
            return new BindingView(binding.isExecutionEnabled(), executable, binding.firstLiveValidated(), wallet.getId(), walletUsable,
                    wallet.getWebProviderCode(), wallet.getCredential(), wallet.getWebProvider(), baseAmount(binding, plan.day()),
                    userBaseSum);
        });
    }

    private BigDecimal baseAmount(RainbowLiveBinding binding, LocalDate day) {
        return runs.findByPresetAndDay(binding.getPreset(), day).map(run -> run.getConfig()).map(RainbowAtrConfig::getBaseAmount)
                .map(BigDecimal::valueOf).orElse(BigDecimal.ZERO);
    }

    private Map<String, InstrumentInfo> instrumentsOf(WebProvider provider) {
        return catalog.liveInstruments(provider).stream().collect(Collectors.toMap(InstrumentInfo::instId, Function.identity(), (a, b) -> a));
    }
}
