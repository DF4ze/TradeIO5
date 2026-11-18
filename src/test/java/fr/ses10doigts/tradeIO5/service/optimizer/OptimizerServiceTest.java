package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerService.OptimizerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Garde-fous du service : un seul run à la fois, annulation, durée de vie des résultats, lancement tracé. */
class OptimizerServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");
    private static final OptimizerRunRequest REQUEST = new OptimizerRunRequest("BTC", 1L, 5000.0, LocalDate.parse("2026-09-01"), "BALANCED",
            10.0, null, null);

    private final ReplayInputsLoader loader = mock(ReplayInputsLoader.class);
    private final AdminActionLogService log = mock(AdminActionLogService.class);
    private final DomainClock clock = mock(DomainClock.class);
    private final User admin = User.builder().id(1L).username("root").build();
    private final CountDownLatch release = new CountDownLatch(1);
    private OptimizerService service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(T0);
        when(loader.load(any(), any())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            throw new IllegalArgumentException("chargement volontairement refusé");
        });
        when(loader.loadReferenceWallet(any(), any())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            throw new IllegalArgumentException("wallet volontairement refusé");
        });
        service = new OptimizerService(loader, log, clock, 1, 30);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        service.shutdown();
    }

    private void awaitState(String id, String state) throws InterruptedException {
        for (int i = 0; i < 200 && !service.get(id).state().equals(state); i++) {
            Thread.sleep(25);
        }
        assertEquals(state, service.get(id).state());
    }

    @Test
    void onlyOneRunAtATimeAndTheRefusalIsTraced() throws InterruptedException {
        OptimizerService.RunView first = service.start(admin, REQUEST);
        assertEquals("RUNNING", first.state());
        OptimizerException e = assertThrows(OptimizerException.class, () -> service.start(admin, REQUEST));
        assertEquals(OptimizerException.Code.BUSY, e.code());
        verify(log).record(eq("root"), eq(AdminAction.RUN), eq("optimizer:BTC"), any(), eq(AdminActionOutcome.OK));
        verify(log).record(eq("root"), eq(AdminAction.RUN), eq("optimizer:BTC"), any(), eq(AdminActionOutcome.REFUSED));
        release.countDown();
        awaitState(first.id(), "FAILED");
        assertTrue(service.get(first.id()).error().contains("refusé"));
        // le run précédent est terminé : un nouveau peut démarrer
        assertEquals("RUNNING", service.start(admin, REQUEST).state());
    }

    @Test
    void cancelFreesTheSlotAndKeepsTheStateReadable() {
        OptimizerService.RunView first = service.start(admin, REQUEST);
        assertEquals("CANCELLED", service.cancel(first.id()).state());
        assertEquals("CANCELLED", service.get(first.id()).state());
        assertEquals("RUNNING", service.start(admin, REQUEST).state());
    }

    @Test
    void finishedResultsExpireAfterTheTtl() throws InterruptedException {
        OptimizerService.RunView first = service.start(admin, REQUEST);
        service.cancel(first.id());
        when(clock.now()).thenReturn(T0.plusSeconds(29 * 60));
        assertEquals("CANCELLED", service.get(first.id()).state());
        when(clock.now()).thenReturn(T0.plusSeconds(31 * 60));
        assertEquals(OptimizerException.Code.NOT_FOUND, assertThrows(OptimizerException.class, () -> service.get(first.id())).code());
    }

    @Test
    void unknownRunIsNotFound() {
        assertEquals(OptimizerException.Code.NOT_FOUND, assertThrows(OptimizerException.class, () -> service.get("nope")).code());
        assertEquals(OptimizerException.Code.NOT_FOUND, assertThrows(OptimizerException.class, () -> service.cancel("nope")).code());
    }

    @Test
    void invalidProfileIsRejectedBeforeAnyRun() {
        OptimizerRunRequest bad = new OptimizerRunRequest("BTC", 1L, 5000.0, LocalDate.parse("2026-09-01"), "NOPE", 10.0, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.start(admin, bad));
    }

    @Test
    void policyModeSharesTheSingleRunSlotAndIsTraced() throws InterruptedException {
        OptimizerRunRequest policy = OptimizerRunRequest.policyL2(null, null, null, null, null, null);
        OptimizerService.RunView first = service.start(admin, policy);
        assertEquals("RUNNING", first.state());
        assertEquals(OptimizerException.Code.BUSY, assertThrows(OptimizerException.class, () -> service.start(admin, REQUEST)).code());
        assertEquals(OptimizerException.Code.BUSY, assertThrows(OptimizerException.class, () -> service.start(admin, policy)).code());
        verify(log).record(eq("root"), eq(AdminAction.RUN), eq("optimizer:policy-l2"), any(), eq(AdminActionOutcome.OK));
        release.countDown();
        awaitState(first.id(), "FAILED");
        assertTrue(service.get(first.id()).error().contains("refusé"));
    }

    @Test
    void policyModeRejectsUnknownProfileBeforeAnyRun() {
        OptimizerRunRequest bad = OptimizerRunRequest.policyL2(null, java.util.List.of("NOPE"), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.start(admin, bad));
    }
}
