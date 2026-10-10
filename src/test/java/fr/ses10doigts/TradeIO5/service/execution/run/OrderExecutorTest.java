package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlanAsset;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
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
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialResolver;
import fr.ses10doigts.tradeIO5.service.execution.exchange.FakeSpotExchange;
import fr.ses10doigts.tradeIO5.service.execution.exchange.FakeSpotExchange.Mode;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderExecutor sur FakeSpotExchange : modes, portes, plafonds, chaîne, kill switch en cours d'exécution")
class OrderExecutorTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 9);

    private final FakeSpotExchange exchange = new FakeSpotExchange();
    private final User user = User.builder().id(1L).build();
    private final ExecutionControl control = ExecutionControl.initial(NOW);
    private final Map<Long, RainbowLiveOrderStep> stepsById = new HashMap<>();
    private final Map<String, RainbowLiveBinding> bindingsByAsset = new HashMap<>();
    private final WebProvider okx = WebProvider.builder().code(WebProviderCode.OKX).apiBaseUrl("http://unused").build();
    private final InstrumentInfo btcUsdc = new InstrumentInfo("BTC-USDC", "BTC", "USDC", new BigDecimal("0.00001"),
            new BigDecimal("0.00000001"), new BigDecimal("0.1"));
    private final InstrumentInfo ethUsdc = new InstrumentInfo("ETH-USDC", "ETH", "USDC", new BigDecimal("0.0001"),
            new BigDecimal("0.00000001"), new BigDecimal("0.01"));

    private RainbowLiveOrderPlanRepository plans;
    private RainbowLiveOrderStepRepository steps;
    private TradeCredentialResolver credentials;
    private StepRequoter requoter;
    private UserRepository users;
    private RainbowLiveOrderPlan plan;
    private BigDecimal baseAmount = new BigDecimal("100");

    @BeforeEach
    void setUp() {
        control.setKillSwitch(false);
        plans = mock(RainbowLiveOrderPlanRepository.class);
        steps = mock(RainbowLiveOrderStepRepository.class);
        credentials = mock(TradeCredentialResolver.class);
        requoter = mock(StepRequoter.class);
        users = mock(UserRepository.class);
        when(steps.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(stepsById.get(i.getArgument(0, Long.class))));
        when(steps.save(any())).thenAnswer(i -> i.getArgument(0));
        when(steps.findSentSince(any(), any(), any())).thenReturn(List.of());
        when(plans.transition(anyLong(), any(), any(), any())).thenReturn(1);
        when(credentials.resolve(any(), any())).thenReturn(Optional.of(new ApiCredential()));
        when(requoter.requote(any(), any(), any(), any(), any(), any()))
                .thenReturn(new StepRequoter.Requote(new BigDecimal("0.10"), new BigDecimal("100000")));
        plan = RainbowLiveOrderPlan.builder().id(1L).user(user).day(DAY).pass(RainbowLivePass.T2355).status(PlanStatus.PLANNED)
                .expiresAt(NOW.plusSeconds(3600)).assets(new ArrayList<>()).steps(new ArrayList<>()).build();
        when(plans.findById(1L)).thenReturn(Optional.of(plan));
    }

    private void addAsset(String symbol, InstrumentInfo instrument, String quoteAmount, long stepId) {
        RainbowLiveOrderPlanAsset asset = RainbowLiveOrderPlanAsset.builder().assetSymbol(symbol).walletId(10L)
                .costPct(new BigDecimal("0.10")).build();
        plan.getAssets().add(asset);
        BigDecimal quote = new BigDecimal(quoteAmount);
        RainbowLiveOrderStep step = RainbowLiveOrderStep.builder().id(stepId).plan(plan).rank(1).assetSymbol(symbol)
                .instId(instrument.instId()).side(StepSide.BUY).ordType(StepOrderType.LIMIT_IOC).px(new BigDecimal("100000"))
                .sz(quote.divide(new BigDecimal("100000"), 8, java.math.RoundingMode.DOWN)).quoteAmount(quote)
                .clOrdId("tio" + stepId + "abc").feePct(new BigDecimal("0.05")).spreadPct(new BigDecimal("0.03"))
                .slippagePct(new BigDecimal("0.02")).status(StepStatus.PLANNED).build();
        plan.getSteps().add(step);
        stepsById.put(stepId, step);
        Wallet wallet = Wallet.builder().id(10L).name("w").source(WalletSource.EXCHANGE).enabled(true)
                .credential(ApiCredential.builder().enabled(true).build()).webProviderCode(WebProviderCode.OKX).webProvider(okx).build();
        bindingsByAsset.put(symbol, RainbowLiveBinding.builder().user(user).assetSymbol(symbol)
                .preset(RainbowLivePreset.builder().id(1L).build()).wallet(wallet).tradability(BindingCheckStatus.OK)
                .executionEnabled(true).firstLiveApprovedAt(NOW).firstLiveConfirmedAt(NOW).build());
    }

    private OrderExecutor executor(String mode) {
        RainbowLiveBindingRepository bindings = mock(RainbowLiveBindingRepository.class);
        when(bindings.findByUserAndAssetSymbol(any(), any())).thenAnswer(i -> Optional.ofNullable(bindingsByAsset.get(i.getArgument(1))));
        when(bindings.findByUserOrderByPriorityAscAssetSymbolAsc(any())).thenAnswer(i -> new ArrayList<>(bindingsByAsset.values()));
        RainbowLiveRunRepository runs = mock(RainbowLiveRunRepository.class);
        when(runs.findByPresetAndDay(any(), any())).thenAnswer(i -> Optional.of(RainbowLiveRun.builder()
                .config(RainbowAtrConfig.builder().baseAmount(baseAmount.doubleValue()).build()).build()));
        InstrumentCatalog catalog = mock(InstrumentCatalog.class);
        when(catalog.liveInstruments(any())).thenReturn(List.of(btcUsdc, ethUsdc));
        ExecutionControlService controlService = mock(ExecutionControlService.class);
        when(controlService.current()).thenAnswer(i -> control);
        @SuppressWarnings("unchecked")
        ObjectProvider<SpotOrderPort> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(exchange);
        WalletRepository wallets = mock(WalletRepository.class);
        when(wallets.findById(anyLong())).thenAnswer(i -> Optional.of(bindingsByAsset.values().iterator().next().getWallet()));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        OrderEventLog events = mock(OrderEventLog.class);
        boolean live = "LIVE".equals(mode);
        ExecutionSettings settings = new ExecutionSettings(mode, Duration.ofHours(1), new BigDecimal("0.1"), live, live);
        StepRunner runner = new StepRunner(ports, steps, mock(FillRecorder.class), events, new FixedDomainClock(NOW), tm,
                Duration.ZERO, 2);
        return new OrderExecutor(settings, controlService, ports, plans, steps, bindings, runs, wallets, users,
                credentials, catalog, requoter, runner, events, new FeeTest(), new FixedDomainClock(NOW), tm);
    }

    private ExecutionResult runLive() {
        return executor("LIVE").execute(1L);
    }

    @Test
    @DisplayName("OFF et DRY_RUN : zéro appel au port, plan intact ; executeAll ne touche à rien")
    void offAndDryRun() {
        addAsset("BTC", btcUsdc, "100", 100L);
        for (String mode : new String[]{"OFF", "DRY_RUN"}) {
            ExecutionResult r = executor(mode).execute(1L);
            assertEquals(ExecutionBlockReason.MODE_NOT_LIVE, r.blocked().get("*"));
            assertEquals(0, exchange.totalCalls());
            assertEquals(PlanStatus.PLANNED, plan.getStatus());
            assertEquals(0, executor(mode).executeAll(DAY, RainbowLivePass.T2355).executed());
        }
        verify(users, never()).findByEnabledTrueAndArchivedAtIsNull();
    }

    @Test
    @DisplayName("Kill switch engagé : aucun ordre")
    void killSwitch() {
        addAsset("BTC", btcUsdc, "100", 100L);
        control.setKillSwitch(true);
        assertEquals(ExecutionBlockReason.KILL_SWITCH, runLive().blocked().get("*"));
        assertEquals(0, exchange.totalCalls());
    }

    @Test
    @DisplayName("Plan expiré ou non PLANNED : aucun ordre")
    void expiredOrWrongStatus() {
        addAsset("BTC", btcUsdc, "100", 100L);
        plan.setExpiresAt(NOW.minusSeconds(1));
        assertEquals(ExecutionBlockReason.PLAN_EXPIRED, runLive().blocked().get("*"));
        plan.setExpiresAt(NOW.plusSeconds(60));
        plan.setStatus(PlanStatus.BLOCKED);
        assertEquals(ExecutionBlockReason.PLAN_NOT_EXECUTABLE, runLive().blocked().get("*"));
        assertEquals(0, exchange.totalCalls());
    }

    @Test
    @DisplayName("Chemin nominal : un ordre, EXECUTED, étape FILLED avec quantités nettes")
    void happyPath() {
        addAsset("BTC", btcUsdc, "100", 100L);
        ExecutionResult r = runLive();
        assertEquals(PlanStatus.EXECUTED, r.status());
        assertEquals(1, r.sent());
        assertEquals(1, exchange.submitCount());
        assertEquals(StepStatus.FILLED, stepsById.get(100L).getStatus());
        assertNull(plan.getExecutionBlockReason());
    }

    @Test
    @DisplayName("Binding non activé, 1er ordre non validé (armé seul), credential absente, non tradable : bloqué, zéro appel")
    void assetGates() {
        addAsset("BTC", btcUsdc, "100", 100L);
        RainbowLiveBinding b = bindingsByAsset.get("BTC");
        b.setExecutionEnabled(false);
        assertEquals(ExecutionBlockReason.BINDING_DISABLED, runLive().blocked().get("BTC"));
        b.setExecutionEnabled(true);
        b.setFirstLiveConfirmedAt(null);
        assertEquals(ExecutionBlockReason.FIRST_LIVE_NOT_VALIDATED, runLive().blocked().get("BTC"));
        b.setFirstLiveConfirmedAt(NOW);
        when(credentials.resolve(any(), any())).thenReturn(Optional.empty());
        assertEquals(ExecutionBlockReason.CREDENTIAL_INVALID, runLive().blocked().get("BTC"));
        when(credentials.resolve(any(), any())).thenReturn(Optional.of(new ApiCredential()));
        b.setTradability(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT);
        assertEquals(ExecutionBlockReason.NOT_TRADABLE, runLive().blocked().get("BTC"));
        assertEquals(0, exchange.totalCalls());
    }

    @Test
    @DisplayName("Plafonds : par ordre (× baseAmount), absolu par ordre, par jour (absolu)")
    void caps() {
        addAsset("BTC", btcUsdc, "150", 100L);
        assertEquals(ExecutionBlockReason.CAP_ORDER, runLive().blocked().get("BTC"));
        plan.setStatus(PlanStatus.PLANNED);
        stepsById.get(100L).setQuoteAmount(new BigDecimal("600"));
        baseAmount = new BigDecimal("1000");
        assertEquals(ExecutionBlockReason.CAP_ABSOLUTE_ORDER, runLive().blocked().get("BTC"));
        plan.setStatus(PlanStatus.PLANNED);
        stepsById.get(100L).setQuoteAmount(new BigDecimal("400"));
        RainbowLiveOrderStep earlier = RainbowLiveOrderStep.builder().id(7L).assetSymbol("BTC").instId("BTC-USDC")
                .quoteAmount(new BigDecimal("700")).status(StepStatus.FILLED).build();
        when(steps.findSentSince(any(), any(), any())).thenReturn(List.of(earlier));
        assertEquals(ExecutionBlockReason.CAP_ABSOLUTE_DAY, runLive().blocked().get("BTC"));
        assertEquals(0, exchange.totalCalls());
    }

    @Test
    @DisplayName("Plafond jour relatif : somme des ordres du jour > multiplicateur × somme des baseAmount")
    void dayMultiplier() {
        addAsset("BTC", btcUsdc, "100", 100L);
        when(steps.findSentSince(any(), any(), any())).thenReturn(List.of(RainbowLiveOrderStep.builder().id(7L)
                .assetSymbol("BTC").instId("BTC-USDC").quoteAmount(new BigDecimal("150")).status(StepStatus.FILLED).build()));
        assertEquals(ExecutionBlockReason.CAP_DAY, runLive().blocked().get("BTC"));
        assertEquals(0, exchange.totalCalls());
    }

    @Test
    @DisplayName("Kill switch levé en cours d'exécution : le 1er actif part, le 2ᵉ n'est pas envoyé")
    void killSwitchMidRun() {
        addAsset("BTC", btcUsdc, "100", 100L);
        addAsset("ETH", ethUsdc, "100", 200L);
        exchange.onSubmit(r -> control.setKillSwitch(true));
        ExecutionResult r = runLive();
        assertEquals(1, exchange.submitCount());
        assertEquals(ExecutionBlockReason.KILL_SWITCH, r.blocked().get("ETH"));
        assertEquals(StepStatus.PLANNED, stepsById.get(200L).getStatus());
        assertEquals(PlanStatus.PARTIAL, r.status());
    }

    @Test
    @DisplayName("Re-devis : coût en dérive ou rouge => étape non envoyée")
    void requoteDrift() {
        addAsset("BTC", btcUsdc, "100", 100L);
        when(requoter.requote(any(), any(), any(), any(), any(), any()))
                .thenReturn(new StepRequoter.Requote(new BigDecimal("0.20"), new BigDecimal("100000")));
        assertEquals(0, runLive().sent());
        when(requoter.requote(any(), any(), any(), any(), any(), any()))
                .thenReturn(new StepRequoter.Requote(new BigDecimal("5"), new BigDecimal("100000")));
        assertEquals(0, runLive().sent());
        assertEquals(0, exchange.totalCalls());
        assertEquals(StepStatus.PLANNED, stepsById.get(100L).getStatus());
    }

    @Test
    @DisplayName("Remplissage partiel : plan PARTIAL, aucun ordre compensatoire")
    void partial() {
        addAsset("BTC", btcUsdc, "100", 100L);
        exchange.mode(Mode.PARTIAL);
        ExecutionResult r = runLive();
        assertEquals(PlanStatus.PARTIAL, r.status());
        assertEquals(1, exchange.submitCount());
    }

    @Test
    @DisplayName("Timeout : étape UNKNOWN, plan reste EXECUTING ; la réconciliation clôt sans renvoyer")
    void timeoutThenReconcile() {
        addAsset("BTC", btcUsdc, "100", 100L);
        exchange.mode(Mode.TIMEOUT_AFTER_ACCEPT);
        OrderExecutor ex = executor("LIVE");
        assertEquals(PlanStatus.EXECUTING, ex.execute(1L).status());
        assertEquals(StepStatus.UNKNOWN, stepsById.get(100L).getStatus());
        when(plans.findById(1L)).thenReturn(Optional.of(plan));
        assertEquals(PlanStatus.EXECUTED, ex.reconcile(1L).status());
        assertEquals(1, exchange.submitCount());
        verify(plans, never()).transition(eq(1L), eq(PlanStatus.EXECUTING), eq(PlanStatus.EXECUTING), any());
    }

    @Test
    @DisplayName("Plan déjà EXECUTING : jamais renvoyé, seulement réconcilié")
    void executingIsNotResent() {
        addAsset("BTC", btcUsdc, "100", 100L);
        plan.setStatus(PlanStatus.EXECUTING);
        stepsById.get(100L).setStatus(StepStatus.PLANNED);
        runLive();
        assertEquals(0, exchange.submitCount());
    }
}
