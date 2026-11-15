package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.EpisodeReportDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationCreatedDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullSimulationResultDto;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssets;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Simulation FULL : garde-fous communs (1 active/utilisateur, propriété, libération des épisodes)")
class ExpertSimulationFullTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-07T10:00:00Z"));
    private final DomainClock clock = now::get;
    private final ExpertContext alice = new ExpertContext(User.builder().id(1L).build(), false);
    private final ExpertContext bob = new ExpertContext(User.builder().id(2L).build(), false);
    private FullSimulationService full;
    private EpisodeResultStore store;
    private ExpertSimulationService service;
    private int seq;

    @BeforeEach
    void setUp() {
        full = mock(FullSimulationService.class);
        store = mock(EpisodeResultStore.class);
        when(full.resolve(any(), any())).thenReturn(new FullSimulationService.Plan(1L, null, null, null, null, false, List.of()));
        service = new ExpertSimulationService(mock(SimulationRequestResolver.class), mock(DailyDataLoader.class), mock(SimulationEligibility.class),
                new ExpertSimulationSettings(2, 4, 10, Duration.ofSeconds(60), Duration.ofMinutes(30), 5), clock, mock(ExpertAssets.class), full, store);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static SimulationRequest fullRequest() {
        return new SimulationRequest("FULL", null, null, null, null, null, null, null, null, null);
    }

    private FullSimulationResultDto result(String resultId) {
        return new FullSimulationResultDto(null, null, List.of(), List.of(),
                new EpisodeReportDto(resultId, null, null, List.of(), List.of(), null, 0.0, List.of()), null);
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
    @DisplayName("Cycle de vie : DONE avec fullResult, result classique absent")
    void lifecycle() throws Exception {
        when(full.compute(any(), any(), any())).thenReturn(result("r1"));
        SimulationCreatedDto created = service.startSimulation(alice, fullRequest());
        SimulationDto done = await(alice, created.id());
        assertEquals("DONE", done.state());
        assertEquals("FULL", done.type());
        assertNotNull(done.fullResult());
        assertEquals("r1", done.fullResult().episodes().resultId());
    }

    @Test
    @DisplayName("Une seule simulation active par utilisateur (409), l'autre utilisateur n'est pas bloqué")
    void oneActivePerUser() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(full.compute(any(), any(), any())).thenAnswer(inv -> {
            release.await();
            return result("r" + (++seq));
        });
        SimulationCreatedDto first = service.startSimulation(alice, fullRequest());
        ExpertApiException e = assertThrows(ExpertApiException.class, () -> service.startSimulation(alice, fullRequest()));
        assertEquals(ExpertApiException.Code.SIMULATION_ALREADY_RUNNING, e.getCode());
        assertNotNull(service.startSimulation(bob, fullRequest()));
        release.countDown();
        assertEquals("DONE", await(alice, first.id()).state());
    }

    @Test
    @DisplayName("Résultat visible du seul créateur")
    void ownerOnly() throws Exception {
        when(full.compute(any(), any(), any())).thenReturn(result("r1"));
        SimulationCreatedDto created = service.startSimulation(alice, fullRequest());
        await(alice, created.id());
        ExpertApiException e = assertThrows(ExpertApiException.class, () -> service.get(bob, created.id()));
        assertEquals(ExpertApiException.Code.SIMULATION_NOT_FOUND, e.getCode());
    }

    @Test
    @DisplayName("DELETE, remplacement et TTL libèrent les épisodes mémorisés")
    void releasesEpisodes() throws Exception {
        when(full.compute(any(), any(), any())).thenAnswer(inv -> result("r" + (++seq)));
        SimulationCreatedDto a = service.startSimulation(alice, fullRequest());
        await(alice, a.id());
        service.delete(alice, a.id());
        verify(store).release(1L, "r1");
        SimulationCreatedDto b = service.startSimulation(alice, fullRequest());
        await(alice, b.id());
        SimulationCreatedDto c = service.startSimulation(alice, fullRequest()); // remplace b (même utilisateur, type, « actif » = wallet entier)
        verify(store).release(1L, "r2");
        await(alice, c.id());
        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertThrows(ExpertApiException.class, () -> service.get(alice, c.id()));
        verify(store).release(1L, "r3");
    }

    @Test
    @DisplayName("Erreur métier du calcul => FAILED avec son code")
    void failureKeepsCode() throws Exception {
        when(full.compute(any(), any(), any())).thenThrow(new ExpertApiException(ExpertApiException.Code.SIMULATION_DATA_SUBSTITUTED, "substitué"));
        SimulationDto dto = await(alice, service.startSimulation(alice, fullRequest()).id());
        assertEquals("FAILED", dto.state());
        assertEquals("SIMULATION_DATA_SUBSTITUTED", dto.error().code());
        assertTrue(dto.fullResult() == null);
    }
}
