package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderStepRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.ExecutionBlockedException;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.ExecutionGuard;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialResolver;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static fr.ses10doigts.tradeIO5.service.execution.run.OrderEventLog.payload;

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

    /** Récapitulatif d'un déclenchement par jour et passe (job). */
    public record RunSummary(LocalDate day, RainbowLivePass pass, int executed, int blocked, int skipped, int errors) {
    }

    private record StepView(Long id, int rank, String asset, String instId, StepSide side, BigDecimal sz, BigDecimal px,
                            BigDecimal quoteAmount, String clOrdId, BigDecimal plannedCostPct, StepStatus status,
                            BigDecimal sentSz, BigDecimal mid) {
        boolean bridge() {
            return !instId.startsWith(asset + "-");
        }
    }

    private record AssetView(String symbol, Long walletId, BigDecimal costPct) {
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
        private int sent;
    }

    private final ExecutionSettings settings;
    private final ExecutionControlService control;
    private final ObjectProvider<SpotOrderPort> ports;
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
    private final TransactionTemplate tx;

    public OrderExecutor(ExecutionSettings settings, ExecutionControlService control, ObjectProvider<SpotOrderPort> ports,
                         RainbowLiveOrderPlanRepository plans, RainbowLiveOrderStepRepository steps,
                         RainbowLiveBindingRepository bindings, RainbowLiveRunRepository runs, WalletRepository wallets,
                         UserRepository users, TradeCredentialResolver credentials, InstrumentCatalog catalog,
                         StepRequoter requoter, StepRunner stepRunner, OrderEventLog events, FeeTest feeTest, DomainClock clock,
                         PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.control = control;
        this.ports = ports;
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

    /** Exécute les plans courants {@code PLANNED} de tous les utilisateurs actifs pour (jour, passe). */
    public RunSummary executeAll(LocalDate day, RainbowLivePass pass) {
        if (!settings.liveExecution()) {
            log.info("Exécution : mode {} => aucun ordre (jour {} passe {})", settings.getMode(), day, pass);
            return new RunSummary(day, pass, 0, 0, 0, 0);
        }
        int executed = 0;
        int blocked = 0;
        int skipped = 0;
        int errors = 0;
        for (User user : users.findByEnabledTrueAndArchivedAtIsNull()) {
            Optional<RainbowLiveOrderPlan> plan = plans.findFirstByUserAndDayAndPassOrderByRevisionDesc(user, day, pass);
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
        RunSummary summary = new RunSummary(day, pass, executed, blocked, skipped, errors);
        log.info("Exécution : {}", summary);
        return summary;
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
            List<StepView> assetSteps = plan.steps().stream().filter(s -> s.asset().equals(asset.symbol()))
                    .sorted(Comparator.comparingInt(StepView::rank)).toList();
            if (assetSteps.isEmpty()) {
                continue;
            }
            if (!liveAllowed()) {
                block(plan, run, asset.symbol(), ExecutionBlockReason.KILL_SWITCH);
                continue;
            }
            try {
                runAsset(plan, asset, assetSteps, run);
            } catch (RuntimeException e) {
                log.error("Exécution du plan {} : erreur inattendue sur {}", planId, asset.symbol(), e);
                events.record(planId, null, null, OrderEventType.ALERT, payload("asset", asset.symbol(), "reason", "UNEXPECTED_ERROR",
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
        if (!settings.liveExecution() || ports.getIfAvailable() == null) {
            return ExecutionResult.blockedGlobally(planId, plan.status(),
                    settings.liveExecution() ? ExecutionBlockReason.PORT_UNAVAILABLE : ExecutionBlockReason.MODE_NOT_LIVE);
        }
        Run run = new Run();
        for (StepView step : plan.steps()) {
            if (!step.status().inFlight()) {
                continue;
            }
            AssetView asset = plan.assets().stream().filter(a -> a.symbol().equals(step.asset())).findFirst().orElse(null);
            Wallet wallet = asset == null ? null : wallets.findById(asset.walletId()).orElse(null);
            Optional<ApiCredential> trade = wallet == null || wallet.getWebProviderCode() == null ? Optional.empty()
                    : credentials.resolve(plan.user(), wallet.getWebProviderCode());
            if (trade.isEmpty()) {
                log.warn("Réconciliation du plan {} : credential TRADE indisponible pour {}", planId, step.clOrdId());
                run.blocks.put(step.asset(), ExecutionBlockReason.CREDENTIAL_INVALID);
                continue;
            }
            InstrumentInfo instrument = instrumentsOf(wallet.getWebProvider()).get(step.instId());
            if (instrument == null) {
                run.blocks.put(step.asset(), ExecutionBlockReason.PORT_UNAVAILABLE);
                continue;
            }
            stepRunner.reconcile(new StepRunner.StepContext(planId, wallet.getId(), step.id(), step.clOrdId(), step.instId(),
                    step.side(), step.sentSz() != null ? step.sentSz() : step.sz(), step.px(), instrument, trade.get(), step.mid()));
        }
        return finish(planId, run);
    }

    // ------------------------------------------------------------------ gardes

    private Optional<ExecutionBlockReason> globalBlock(PlanView plan) {
        if (!settings.liveExecution()) {
            return Optional.of(ExecutionBlockReason.MODE_NOT_LIVE);
        }
        if (ports.getIfAvailable() == null) {
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

    private void block(PlanView plan, Run run, String asset, ExecutionBlockReason reason) {
        run.blocks.put(asset, reason);
        log.warn("Plan {} : exécution de {} bloquée ({})", plan.id(), asset, reason);
        events.record(plan.id(), null, null, OrderEventType.BLOCKED, payload("asset", asset, "reason", reason));
    }

    // ------------------------------------------------------------------ un actif

    private void runAsset(PlanView plan, AssetView asset, List<StepView> assetSteps, Run run) {
        BindingView binding = loadBinding(plan, asset.symbol());
        if (binding == null || !asset.walletId().equals(binding.walletId()) || !binding.walletUsable()) {
            block(plan, run, asset.symbol(), ExecutionBlockReason.NO_BINDING);
            return;
        }
        if (!binding.enabled()) {
            block(plan, run, asset.symbol(), ExecutionBlockReason.BINDING_DISABLED);
            return;
        }
        if (!binding.executable()) {
            block(plan, run, asset.symbol(), ExecutionBlockReason.NOT_TRADABLE);
            return;
        }
        Optional<ApiCredential> trade = credentials.resolve(plan.user(), binding.provider());
        if (trade.isEmpty()) {
            block(plan, run, asset.symbol(), ExecutionBlockReason.CREDENTIAL_INVALID);
            return;
        }
        if (!binding.firstLiveValidated()) {
            block(plan, run, asset.symbol(), ExecutionBlockReason.FIRST_LIVE_NOT_VALIDATED);
            return;
        }
        Optional<ExecutionBlockReason> cap = capBlock(plan, asset, assetSteps, binding);
        if (cap.isPresent()) {
            block(plan, run, asset.symbol(), cap.get());
            return;
        }
        Map<String, InstrumentInfo> instruments;
        try {
            instruments = instrumentsOf(binding.webProvider());
        } catch (RuntimeException e) {
            block(plan, run, asset.symbol(), ExecutionBlockReason.PORT_UNAVAILABLE);
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
            if (step.quoteAmount().compareTo(ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD) > 0) {
                return Optional.of(ExecutionBlockReason.CAP_ABSOLUTE_ORDER);
            }
            if (!step.bridge()) {
                if (step.quoteAmount().compareTo(orderCap) > 0) {
                    return Optional.of(ExecutionBlockReason.CAP_ORDER);
                }
                assetNotional = assetNotional.add(step.quoteAmount());
            }
        }
        Instant dayStart = clock.now().truncatedTo(ChronoUnit.DAYS);
        BigDecimal daySpent = tx.execute(status -> steps.findSentSince(plan.user(), dayStart, SENT).stream()
                .filter(s -> s.getInstId().startsWith(s.getAssetSymbol() + "-"))
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
        StepView bridge = null;
        BigDecimal bridgeReceived = null;
        String bridgeReceivedCcy = null;
        boolean stop = false;
        for (StepView step : assetSteps) {
            if (stop) {
                skip(plan, step, "PREVIOUS_STEP_NOT_EXECUTED");
                continue;
            }
            if (!liveAllowed()) {
                skip(plan, step, ExecutionBlockReason.KILL_SWITCH.name());
                run.blocks.put(asset.symbol(), ExecutionBlockReason.KILL_SWITCH);
                stop = true;
                continue;
            }
            InstrumentInfo instrument = instruments.get(step.instId());
            if (instrument == null) {
                skip(plan, step, "INSTRUMENT_UNKNOWN");
                stop = true;
                continue;
            }
            BigDecimal sz = step.sz();
            if (!step.bridge() && bridge != null) {
                if (bridgeReceived == null || bridgeReceived.signum() <= 0
                        || !instrument.quote().equalsIgnoreCase(bridgeReceivedCcy)) {
                    skip(plan, step, "BRIDGE_RECEIVED_UNUSABLE");
                    stop = true;
                    continue;
                }
                BigDecimal bridgeTarget = bridge.quoteAmount().divide(BigDecimal.ONE.add(
                        ExecutionDefaults.BRIDGE_FEE_MARGIN_PCT.divide(HUNDRED, 18, RoundingMode.HALF_UP)), 18, RoundingMode.HALF_UP);
                BigDecimal heldBefore = step.quoteAmount().subtract(bridgeTarget).max(BigDecimal.ZERO);
                BigDecimal affordable = PathFinder.roundToStep(heldBefore.add(bridgeReceived).divide(step.px(), 18, RoundingMode.DOWN),
                        instrument.lotSz(), RoundingMode.DOWN);
                sz = sz.min(affordable);
            }
            if (sz.signum() <= 0 || sz.compareTo(instrument.minSz()) < 0) {
                skip(plan, step, "BELOW_MIN");
                stop = true;
                continue;
            }
            StepRequoter.Requote quote;
            try {
                quote = requoter.requote(binding.provider(), binding.readCredential(), instrument, step.side(), sz,
                        sz.multiply(step.px()));
            } catch (RuntimeException e) {
                skip(plan, step, "REQUOTE_UNAVAILABLE");
                stop = true;
                continue;
            }
            BigDecimal others = assetSteps.stream().filter(s -> !s.id().equals(step.id())).map(StepView::plannedCostPct)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal projected = others.add(quote.costPct());
            events.record(plan.id(), step.id(), step.clOrdId(), OrderEventType.STEP_REQUOTED,
                    payload("legCostPct", quote.costPct(), "projectedPct", projected, "planCostPct", planCost, "mid", quote.mid()));
            if (feeTest.level(projected) == FeeTestLevel.RED) {
                skip(plan, step, "COST_RED");
                stop = true;
                continue;
            }
            if (projected.compareTo(planCost.add(ExecutionDefaults.COST_DRIFT_POINTS)) > 0) {
                skip(plan, step, "COST_DRIFT");
                stop = true;
                continue;
            }
            if (!liveAllowed()) {
                skip(plan, step, ExecutionBlockReason.KILL_SWITCH.name());
                run.blocks.put(asset.symbol(), ExecutionBlockReason.KILL_SWITCH);
                stop = true;
                continue;
            }
            StepRunner.StepResult result = stepRunner.send(new StepRunner.StepContext(plan.id(), binding.walletId(), step.id(),
                    step.clOrdId(), step.instId(), step.side(), sz, step.px(), instrument, trade, quote.mid()));
            run.sent++;
            if (!result.status().executed()) {
                stop = true;
            } else if (step.bridge()) {
                bridge = step;
                bridgeReceived = result.outcome().received();
                bridgeReceivedCcy = result.outcome().receivedCurrency();
            }
        }
    }

    private void skip(PlanView plan, StepView step, String reason) {
        log.info("Plan {} : étape {} non envoyée ({})", plan.id(), step.clOrdId(), reason);
        events.record(plan.id(), step.id(), step.clOrdId(), OrderEventType.STEP_SKIPPED, payload("reason", reason));
    }

    // ------------------------------------------------------------------ clôture

    /** Statut du plan d'après ses étapes ; relit la base (les étapes ont été mises à jour par le runner). */
    private ExecutionResult finish(Long planId, Run run) {
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
            plan.setExecutionBlockReason(run.blocks.isEmpty() ? null : run.blocks.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(";")));
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
                plan.getSteps().stream().map(s -> new StepView(s.getId(), s.getRank(), s.getAssetSymbol(), s.getInstId(), s.getSide(),
                        s.getSz(), s.getPx(), s.getQuoteAmount(), s.getClOrdId(),
                        s.getFeePct().add(s.getSpreadPct()).add(s.getSlippagePct()), s.getStatus(), s.getSentSz(),
                        s.getQuoteMid())).toList())).orElse(null));
        if (view == null) {
            throw new IllegalArgumentException("Plan inconnu : " + planId);
        }
        return view;
    }

    private BindingView loadBinding(PlanView plan, String asset) {
        return tx.execute(status -> {
            RainbowLiveBinding binding = bindings.findByUserAndAssetSymbol(plan.user(), asset).orElse(null);
            if (binding == null) {
                return null;
            }
            Wallet wallet = binding.getWallet();
            boolean executable;
            try {
                ExecutionGuard.requireExecutable(binding, true);
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
