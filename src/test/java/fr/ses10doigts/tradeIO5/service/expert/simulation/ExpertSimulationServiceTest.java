package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationCreatedDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssets;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpertSimulationServiceTest {

    private static final int REQUIRED = 150;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-07T10:00:00Z"));
    private final DomainClock clock = now::get;
    private SimulationRequestResolver resolver;
    private DailyDataLoader loader;
    private ExpertSimulationService service;
    private final ExpertContext alice = new ExpertContext(User.builder().id(1L).build(), false);
    private final ExpertContext bob = new ExpertContext(User.builder().id(2L).build(), false);

    @BeforeEach
    void setUp() {
        resolver = mock(SimulationRequestResolver.class);
        loader = mock(DailyDataLoader.class);
        ExpertAssets assets = mock(ExpertAssets.class);
        when(assets.requireAsset(anyString())).thenAnswer(inv -> inv.getArgument(0));
        SimulationEligibility eligibility = mock(SimulationEligibility.class);
        when(eligibility.unreachable(anyString(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        var bearBull = RainbowAtrPresets.bearBull("BTC");
        when(resolver.resolve(any(), any(), anyString(), any())).thenAnswer(inv -> new ResolvedSimulation(inv.getArgument(1),
                inv.getArgument(2), TrendMixCalculator.Params.defaults(), RangeMapping.KEEP_PREVIOUS, "Mix",
                bearBull.get(RainbowAtrPresets.BULL), bearBull.get(RainbowAtrPresets.BEAR), null, REQUIRED, "BINANCE"));
        service = newService(new ExpertSimulationSettings(2, 4, 10, Duration.ofSeconds(60), Duration.ofMinutes(30), 5), assets, eligibility);
    }

    private ExpertSimulationService newService(ExpertSimulationSettings settings, ExpertAssets assets, SimulationEligibility eligibility) {
        return new ExpertSimulationService(resolver, loader, eligibility, settings, clock, assets);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private void servesCandles(int count, boolean substituted) {
        List<MarketData> candles = SimulationFixtures.synthetic(count, now.get());
        MarketDataset dataset = MarketDataset.builder().marketDatas(candles).size(count).build();
        when(loader.load(anyString(), any())).thenReturn(new DailyDataset(dataset, MarketDataSource.BINANCE, "BTCUSDT", substituted));
    }

    private static SimulationRequest request(String type, String asset) {
        return new SimulationRequest(type, asset, null, null, null, null, null, null, null, null);
    }

    private SimulationDto await(ExpertContext ctx, String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            SimulationDto dto = service.get(ctx, id);
            if (dto.state().equals("DONE") || dto.state().equals("FAILED")) {
                return dto;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("simulation non terminée");
    }

    @Test
    void lifecycleReachesDoneWithResultAndTtl() throws Exception {
        servesCandles(700, false);
        SimulationCreatedDto created = service.submit(alice, request("AFFECTATION", "BTC"));
        assertEquals("QUEUED", created.state());
        SimulationDto done = await(alice, created.id());
        assertEquals("DONE", done.state());
        assertTrue(done.result() != null && done.error() == null);
        assertEquals(done.finishedAt().plus(Duration.ofMinutes(30)), done.expiresAt());

        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertThrows(ExpertApiException.class, () -> service.get(alice, created.id()));
    }

    @Test
    void insufficientHistoryFailsWithRequiredAndAvailable() throws Exception {
        servesCandles(REQUIRED - 1, false);
        SimulationDto dto = await(alice, service.submit(alice, request("AFFECTATION", "BTC")).id());
        assertEquals("FAILED", dto.state());
        assertEquals("ELIGIBILITY_INSUFFICIENT", dto.error().code());
        assertTrue(dto.error().details().toString().contains("requiredPoints=" + REQUIRED));
        assertTrue(dto.error().details().toString().contains("availablePoints=" + (REQUIRED - 1)));
    }

    @Test
    void exactlyRequiredPointsIsAccepted() throws Exception {
        // la bougie du jour est écartée : REQUIRED bougies fermées suffisent, la plage 24 mois est bornée par l'historique
        servesCandles(REQUIRED, false);
        SimulationDto dto = await(alice, service.submit(alice, request("TREND", "BTC")).id());
        assertEquals("DONE", dto.state(), String.valueOf(dto.error()));
    }

    @Test
    void substitutedProviderIsSignaledInMetaAndWarnings() throws Exception {
        servesCandles(700, true);
        SimulationDto dto = await(alice, service.submit(alice, request("TREND", "BTC")).id());
        assertTrue(dto.result().meta().substituted());
        assertTrue(dto.result().meta().warnings().stream().anyMatch(w -> w.contains("fallback")));
    }

    @Test
    void secondSimulationWhileOneRunsIsRefused() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(loader.load(anyString(), any())).thenAnswer(inv -> {
            release.await();
            return null;
        });
        service.submit(alice, request("TREND", "BTC"));
        ExpertApiException e = assertThrows(ExpertApiException.class, () -> service.submit(alice, request("TREND", "ETH")));
        assertEquals(ExpertApiException.Code.SIMULATION_ALREADY_RUNNING, e.getCode());
        // un autre utilisateur n'est pas concerné
        service.submit(bob, request("TREND", "BTC"));
        release.countDown();
    }

    @Test
    void rateLimitPerUserIsEnforced() throws Exception {
        servesCandles(700, false);
        ExpertSimulationService limited = newService(new ExpertSimulationSettings(2, 4, 2, Duration.ofSeconds(60), Duration.ofMinutes(30), 5),
                mockAssets(), mockEligibility());
        try {
            for (int i = 0; i < 2; i++) {
                String id = limited.submit(alice, request("TREND", "BTC")).id();
                waitUntil(() -> limited.get(alice, id).state().equals("DONE"));
            }
            ExpertApiException e = assertThrows(ExpertApiException.class, () -> limited.submit(alice, request("TREND", "BTC")));
            assertEquals(ExpertApiException.Code.SIMULATION_RATE_LIMITED, e.getCode());
            now.set(now.get().plusSeconds(61));
            limited.submit(alice, request("TREND", "BTC"));
        } finally {
            limited.shutdown();
        }
    }

    @Test
    void relaunchReplacesSameTypeAndAsset() throws Exception {
        servesCandles(700, false);
        String first = service.submit(alice, request("TREND", "BTC")).id();
        await(alice, first);
        String second = service.submit(alice, request("TREND", "BTC")).id();
        assertThrows(ExpertApiException.class, () -> service.get(alice, first));
        assertEquals("TREND", service.get(alice, second).type());
    }

    @Test
    void simulationsAreVisibleToTheirOwnerOnly() throws Exception {
        servesCandles(700, false);
        String id = service.submit(alice, request("TREND", "BTC")).id();
        await(alice, id);
        ExpertApiException e = assertThrows(ExpertApiException.class, () -> service.get(bob, id));
        assertEquals(ExpertApiException.Code.SIMULATION_NOT_FOUND, e.getCode());
        assertThrows(ExpertApiException.class, () -> service.delete(bob, id));
        service.delete(alice, id);
        assertThrows(ExpertApiException.class, () -> service.get(alice, id));
    }

    @Test
    void perUserCapEvictsOldestResult() throws Exception {
        servesCandles(700, false);
        ExpertSimulationService capped = newService(new ExpertSimulationSettings(2, 4, 50, Duration.ofSeconds(60), Duration.ofMinutes(30), 2),
                mockAssets(), mockEligibility());
        try {
            String a = capped.submit(alice, request("TREND", "BTC")).id();
            waitUntil(() -> capped.get(alice, a).state().equals("DONE"));
            now.set(now.get().plusSeconds(1));
            String b = capped.submit(alice, request("TREND", "ETH")).id();
            waitUntil(() -> capped.get(alice, b).state().equals("DONE"));
            now.set(now.get().plusSeconds(1));
            String c = capped.submit(alice, request("TREND", "SOL")).id();
            waitUntil(() -> capped.get(alice, c).state().equals("DONE"));
            assertThrows(ExpertApiException.class, () -> capped.get(alice, a));
            assertFalse(capped.get(alice, c).state().isEmpty());
        } finally {
            capped.shutdown();
        }
    }

    private ExpertAssets mockAssets() {
        ExpertAssets assets = mock(ExpertAssets.class);
        when(assets.requireAsset(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return assets;
    }

    private SimulationEligibility mockEligibility() {
        SimulationEligibility e = mock(SimulationEligibility.class);
        when(e.unreachable(anyString(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        return e;
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition non atteinte");
    }
}
