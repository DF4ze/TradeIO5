package fr.ses10doigts.tradeIO5.service.execution.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.controller.RainbowLiveExecutionPlanController;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.execution.AssetPlanOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Persistance du plan (H2) : idempotence, remplacement par révision, expiration à 5 min, isolation entre users, erreur d'un
 * user sans effet sur les autres, mode OFF, et lecture REST (propriétaire seulement, DTO sans entité).
 */
@DataJpaTest
@Import({RainbowLivePresetService.class, fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver.class,
        fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService.class, OrderPlanQueryService.class,
        OrderPlanServiceTest.ClockConfig.class})
@DisplayName("OrderPlanService : persistance idempotente du plan d'ordres dry-run")
class OrderPlanServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 9);
    private static final Instant NOW = Instant.parse("2026-10-09T23:58:00Z");
    private static final AtomicReference<Instant> CLOCK = new AtomicReference<>(NOW);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return CLOCK::get;
        }
    }

    @Autowired private RainbowLivePresetService presetService;
    @Autowired private OrderPlanQueryService queryService;
    @Autowired private RainbowLiveBindingRepository bindingRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private RainbowLiveOrderPlanRepository planRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DomainClock clock;
    @Autowired private fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderEventRepository eventRepository;

    private final OrderPlanner planner = mock(OrderPlanner.class);
    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private User alice;
    private User bob;
    private OrderPlanService service;

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").enabled(true).build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").enabled(true).build());
        service = service(new ExecutionSettings());
    }

    private OrderPlanService service(ExecutionSettings settings) {
        return new OrderPlanService(userRepository, bindingRepository, runRepository, planRepository, planner, settings, clock,
                transactionManager);
    }

    private void seed(User user, String asset, double amount, boolean enabled) {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor(asset);
        RainbowLivePreset preset = presetService.create(user, new CreateRequest(asset, "live", true, 6, 1000, c.toTuning(), c.toGlobals()));
        Wallet w = walletRepository.save(Wallet.builder().name("OKX " + user.getUsername()).source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).user(user).build());
        bindingRepository.save(RainbowLiveBinding.builder().user(user).assetSymbol(asset).preset(preset).wallet(w).bagPercent(100)
                .priority(0).executionEnabled(enabled).build());
        RainbowLivePassBlock block = RainbowLivePassBlock.builder().liveStatus(PortfolioStatus.OK).liveWalletId(w.getId())
                .liveCashDetail("{\"USDC\":500.0,\"USDT\":0.0}").liveActionType(RainbowLiveAction.BUY)
                .liveActionAmountUsdc(amount).liveActionQuantity(0.001).build();
        runRepository.save(RainbowLiveRun.builder().preset(preset).user(user).assetSymbol(asset).day(DAY).pass2355(block).build());
    }

    private static PlanDraft draft(String asset, PlanStatus status) {
        PlanDraft.StepDraft step = new PlanDraft.StepDraft(1, asset, asset + "-USDT", StepSide.BUY, new BigDecimal("0.5"),
                new BigDecimal("100.3"), new BigDecimal("50"), new BigDecimal("0.05"), new BigDecimal("0.1"), BigDecimal.ZERO,
                PathEstimation.BOOK);
        PlanDraft.AssetDraft a = new PlanDraft.AssetDraft(asset, 0, 1L, RainbowLiveAction.BUY, AssetPlanOutcome.OK, null,
                new BigDecimal("0.15"), FeeTestLevel.GREEN, null, List.of(step));
        return new PlanDraft(List.of(a), status, new BigDecimal("0.15"), FeeTestLevel.GREEN, null);
    }

    @Test
    @DisplayName("Plan créé : révision 1, expire 5 min après, étapes avec clOrdId déterministe")
    void created() {
        seed(alice, "PAXG", 50, true);
        when(planner.plan(anyList())).thenReturn(draft("PAXG", PlanStatus.PLANNED));

        OrderPlanService.PlanSummary summary = service.planAll(DAY, RainbowLivePass.T2355);

        assertEquals(1, summary.planned());
        RainbowLiveOrderPlan plan = planRepository.findFirstByUserAndDayAndPassOrderByRevisionDesc(alice, DAY, RainbowLivePass.T2355).orElseThrow();
        assertEquals(1, plan.getRevision());
        assertEquals(PlanStatus.PLANNED, plan.getStatus());
        assertEquals(NOW.plusSeconds(300), plan.getExpiresAt());
        assertEquals(1, plan.getSteps().size());
        assertEquals(ClOrdIds.of(alice.getId(), "PAXG", DAY, RainbowLivePass.T2355, 1), plan.getSteps().getFirst().getClOrdId());
        assertEquals(64, plan.getInputsHash().length());
    }

    @Test
    @DisplayName("Mêmes entrées (rejeu 23:55) : aucune nouvelle ligne, planner non rappelé")
    void idempotent() {
        seed(alice, "PAXG", 50, true);
        when(planner.plan(anyList())).thenReturn(draft("PAXG", PlanStatus.PLANNED));
        service.planAll(DAY, RainbowLivePass.T2355);

        OrderPlanService.PlanSummary again = service.planAll(DAY, RainbowLivePass.T2355);

        assertEquals(1, again.unchanged());
        assertEquals(1, planRepository.count());
        verify(planner, times(1)).plan(anyList());
    }

    @Test
    @DisplayName("Entrées différentes : ancien plan SUPERSEDED (audit conservé), nouvelle révision courante")
    void superseded() {
        seed(alice, "PAXG", 50, true);
        when(planner.plan(anyList())).thenReturn(draft("PAXG", PlanStatus.PLANNED));
        service.planAll(DAY, RainbowLivePass.T2355);
        RainbowLiveRun run = runRepository.findAll().getFirst();
        run.getPass2355().setLiveActionAmountUsdc(75.0);
        runRepository.saveAndFlush(run);

        OrderPlanService.PlanSummary again = service.planAll(DAY, RainbowLivePass.T2355);

        assertEquals(1, again.planned());
        List<RainbowLiveOrderPlan> all = planRepository.findAll();
        assertEquals(2, all.size());
        assertEquals(PlanStatus.SUPERSEDED, all.stream().filter(p -> p.getRevision() == 1).findFirst().orElseThrow().getStatus());
        assertEquals(PlanStatus.PLANNED, all.stream().filter(p -> p.getRevision() == 2).findFirst().orElseThrow().getStatus());
        assertEquals(1, queryService.plans(alice, DAY, DAY).size(), "les révisions remplacées ne sont pas exposées");
    }

    @Test
    @DisplayName("Expiration : PLANNED devient EXPIRED à 5 min (horloge fixée), BLOCKED reste BLOCKED")
    void expiry() {
        seed(alice, "PAXG", 50, true);
        when(planner.plan(anyList())).thenReturn(draft("PAXG", PlanStatus.PLANNED));
        service.planAll(DAY, RainbowLivePass.T2355);

        CLOCK.set(NOW.plusSeconds(299));
        assertEquals(PlanStatus.PLANNED, queryService.latest(alice).orElseThrow().status());
        CLOCK.set(NOW.plusSeconds(300));
        assertEquals(PlanStatus.EXPIRED, queryService.latest(alice).orElseThrow().status());

        RainbowLiveOrderPlan plan = planRepository.findAll().getFirst();
        plan.setStatus(PlanStatus.BLOCKED);
        assertFalse(plan.isExpiredAt(NOW.plusSeconds(3600)));
    }

    @Test
    @DisplayName("Binding non activé : statut DISABLED enregistré ; sans run du jour : ignoré")
    void disabledAndSkipped() {
        seed(alice, "PAXG", 50, false);
        when(planner.plan(anyList())).thenReturn(new PlanDraft(List.of(), PlanStatus.DISABLED, null, null, null));

        OrderPlanService.PlanSummary summary = service.planAll(DAY, RainbowLivePass.T2355);

        assertEquals(1, summary.disabled());
        assertEquals(1, summary.skipped(), "bob n'a aucun binding/run");
        assertEquals(PlanStatus.DISABLED, planRepository.findAll().getFirst().getStatus());
    }

    @Test
    @DisplayName("Mode OFF : aucun plan, aucune lecture ; LIVE refusé au démarrage ; valeur inconnue refusée")
    void modes() {
        seed(alice, "PAXG", 50, true);

        OrderPlanService.PlanSummary off = service(new ExecutionSettings("OFF", java.time.Duration.ofMinutes(5), new BigDecimal("0.1")))
                .planAll(DAY, RainbowLivePass.T2355);

        assertEquals(0, planRepository.count());
        assertEquals(0, off.planned());
        verify(planner, never()).plan(anyList());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new ExecutionSettings("LIVE", java.time.Duration.ofMinutes(5), new BigDecimal("0.1")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new ExecutionSettings("YOLO", java.time.Duration.ofMinutes(5), new BigDecimal("0.1")));
        assertEquals("DRY_RUN", new ExecutionSettings().getMode().name());
    }

    @Test
    @DisplayName("GET /execution-plans(/latest) : propriétaire seulement, DTO sans secret, 204 sans plan")
    void restIsolation() throws Exception {
        seed(alice, "PAXG", 50, true);
        when(planner.plan(anyList())).thenReturn(draft("PAXG", PlanStatus.PLANNED));
        service.planAll(DAY, RainbowLivePass.T2355);
        when(facade.getConnectedUser()).thenReturn(alice);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveExecutionPlanController(queryService, facade, clock))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();

        mvc.perform(get("/api/rainbow-live/execution-plans/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.assets[0].assetSymbol").value("PAXG"))
                .andExpect(jsonPath("$.assets[0].steps[0].instId").value("PAXG-USDT"))
                .andExpect(jsonPath("$.assets[0].steps[0].ordType").value("LIMIT_IOC"))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.inputsHash").doesNotExist());
        mvc.perform(get("/api/rainbow-live/execution-plans").param("from", "2026-10-01").param("to", "2026-10-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        when(facade.getConnectedUser()).thenReturn(bob);
        mvc.perform(get("/api/rainbow-live/execution-plans/latest")).andExpect(status().isNoContent());
        mvc.perform(get("/api/rainbow-live/execution-plans").param("from", "2026-10-01").param("to", "2026-10-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertTrue(queryService.plans(bob, DAY, DAY).isEmpty());
    }

    @Test
    @DisplayName("GET /execution-plans/{id}/events : audit paginé en lecture seule, propriétaire seulement (404 sinon), taille bornée")
    void eventsAreOwnerScopedAndPaged() throws Exception {
        seed(alice, "PAXG", 50, true);
        when(planner.plan(anyList())).thenReturn(draft("PAXG", PlanStatus.PLANNED));
        service.planAll(DAY, RainbowLivePass.T2355);
        var plan = planRepository.findAll().getFirst();
        for (int i = 0; i < 3; i++) {
            eventRepository.save(fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderEvent.builder().plan(plan)
                    .clOrdId("c" + i).type(fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType.ALERT)
                    .payload("{\"i\":" + i + "}").createdAt(NOW).build());
        }
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveExecutionPlanController(queryService, facade, clock))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
        when(facade.getConnectedUser()).thenReturn(alice);
        mvc.perform(get("/api/rainbow-live/execution-plans/" + plan.getId() + "/events").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].type").value("ALERT"))
                .andExpect(jsonPath("$.items[0].plan").doesNotExist());
        mvc.perform(get("/api/rainbow-live/execution-plans/" + plan.getId() + "/events").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/rainbow-live/execution-plans/" + plan.getId() + "/events").param("size", "100000"))
                .andExpect(jsonPath("$.size").value(100));
        when(facade.getConnectedUser()).thenReturn(bob);
        mvc.perform(get("/api/rainbow-live/execution-plans/" + plan.getId() + "/events")).andExpect(status().isNotFound());
        mvc.perform(get("/api/rainbow-live/execution-plans/999999/events")).andExpect(status().isNotFound());
    }
}
