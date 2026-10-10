package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderStepRepository;
import fr.ses10doigts.tradeIO5.service.execution.exchange.FakeSpotExchange;
import fr.ses10doigts.tradeIO5.service.execution.exchange.FakeSpotExchange.Mode;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderRequest;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StepRunner sur FakeSpotExchange : partiel, timeout, doublon, rejet, réconciliation, net des frais")
class StepRunnerTest {

    private static final InstrumentInfo BTC_USDC = new InstrumentInfo("BTC-USDC", "BTC", "USDC", new BigDecimal("0.00001"),
            new BigDecimal("0.00000001"), new BigDecimal("0.1"));

    private FakeSpotExchange exchange;
    private RainbowLiveOrderStepRepository steps;
    private FillRecorder recorder;
    private OrderEventLog events;
    private RainbowLiveOrderStep step;
    private StepRunner runner;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        exchange = new FakeSpotExchange();
        steps = mock(RainbowLiveOrderStepRepository.class);
        recorder = mock(FillRecorder.class);
        events = mock(OrderEventLog.class);
        step = RainbowLiveOrderStep.builder().id(9L).status(StepStatus.PLANNED).build();
        when(steps.findById(9L)).thenReturn(Optional.of(step));
        when(steps.save(any())).thenAnswer(i -> i.getArgument(0));
        ObjectProvider<SpotOrderPort> ports = mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(exchange);
        runner = new StepRunner(ports, steps, recorder, events, new FixedDomainClock(Instant.parse("2026-10-10T00:00:00Z")),
                mock(PlatformTransactionManager.class), Duration.ZERO, 3);
    }

    private StepRunner.StepContext ctx() {
        return new StepRunner.StepContext(1L, 5L, 9L, "tio9abc", "BTC-USDC", StepSide.BUY, new BigDecimal("0.01"),
                new BigDecimal("100000"), BTC_USDC, new ApiCredential(), new BigDecimal("99990"));
    }

    private List<OrderEventType> eventTypes() {
        ArgumentCaptor<OrderEventType> c = ArgumentCaptor.forClass(OrderEventType.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).record(any(), any(), any(), c.capture(), any());
        return c.getAllValues();
    }

    @Test
    @DisplayName("Rempli : quantités nettes des frais, slippage réel, fills enregistrés, SUBMITTED écrit avant l'envoi")
    void filled() {
        exchange.onSubmit(r -> assertEquals(StepStatus.SUBMITTED, step.getStatus(), "écriture préalable"));
        var result = runner.send(ctx());
        assertEquals(StepStatus.FILLED, result.status());
        assertEquals(StepStatus.FILLED, step.getStatus());
        assertEquals(0, new BigDecimal("0.00999").compareTo(step.getReceivedAmount()));
        assertEquals("BTC", step.getFeeCurrency());
        assertNotNull(step.getRealSlippagePct());
        verify(recorder).record(eq(5L), eq("BTC"), any());
    }

    @Test
    @DisplayName("Partiel : PARTIAL, reliquat journalisé, aucun nouvel ordre")
    void partial() {
        exchange.mode(Mode.PARTIAL);
        var result = runner.send(ctx());
        assertEquals(StepStatus.PARTIAL, result.status());
        assertEquals(0, new BigDecimal("0.005").compareTo(step.getFilledSz()));
        assertEquals(1, exchange.submitCount());
        org.junit.jupiter.api.Assertions.assertTrue(eventTypes().contains(OrderEventType.PARTIAL_REMAINDER));
    }

    @Test
    @DisplayName("Rien rempli (IOC annulé) : CANCELED")
    void noFill() {
        exchange.mode(Mode.NO_FILL);
        assertEquals(StepStatus.CANCELED, runner.send(ctx()).status());
    }

    @Test
    @DisplayName("Timeout après envoi : UNKNOWN, jamais de renvoi ; la réconciliation par clOrdId clôt l'étape")
    void timeoutThenReconcile() {
        exchange.mode(Mode.TIMEOUT_AFTER_ACCEPT);
        assertEquals(StepStatus.UNKNOWN, runner.send(ctx()).status());
        assertEquals(1, exchange.submitCount());
        var reconciled = runner.reconcile(ctx());
        assertEquals(StepStatus.FILLED, reconciled.status());
        assertEquals(1, exchange.submitCount(), "la réconciliation n'envoie jamais d'ordre");
    }

    @Test
    @DisplayName("Timeout, ordre jamais arrivé : UNKNOWN puis réconciliation => introuvable => CANCELED")
    void timeoutNotAccepted() {
        exchange.mode(Mode.TIMEOUT_NOT_ACCEPTED);
        assertEquals(StepStatus.UNKNOWN, runner.send(ctx()).status());
        assertEquals(StepStatus.CANCELED, runner.reconcile(ctx()).status());
        assertEquals(1, exchange.submitCount());
    }

    @Test
    @DisplayName("Ordre non terminal après les tentatives : UNKNOWN")
    void staysLive() {
        exchange.mode(Mode.STAY_LIVE);
        assertEquals(StepStatus.UNKNOWN, runner.send(ctx()).status());
    }

    @Test
    @DisplayName("Doublon de clOrdId : pas de nouvel ordre, relecture de l'état de l'ordre existant")
    void duplicate() {
        runner.send(ctx());
        step.setStatus(StepStatus.PLANNED);
        var again = runner.send(ctx());
        assertEquals(StepStatus.FILLED, again.status());
        assertEquals(1, exchange.submitCount());
    }

    @Test
    @DisplayName("Rejet métier ou clé refusée : REJECTED, aucun fill")
    void rejected() {
        exchange.mode(Mode.REJECT);
        assertEquals(StepStatus.REJECTED, runner.send(ctx()).status());
        assertEquals("51008", step.getLastError());
        exchange.mode(Mode.CREDENTIAL_REJECTED);
        assertEquals(StepStatus.REJECTED, runner.send(new StepRunner.StepContext(1L, 5L, 9L, "tio9abd", "BTC-USDC", StepSide.BUY,
                new BigDecimal("0.01"), new BigDecimal("100000"), BTC_USDC, new ApiCredential(), null)).status());
        verify(recorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("Ordre lu tardivement (introuvable puis visible) : toujours un seul envoi")
    void hiddenThenVisible() {
        exchange.hideQueries(1);
        assertEquals(StepStatus.FILLED, runner.send(ctx()).status());
        assertEquals(1, exchange.submitCount());
    }

    @Test
    @DisplayName("Sans port : échec explicite, rien n'est écrit en SUBMITTED")
    @SuppressWarnings("unchecked")
    void noPort() {
        ObjectProvider<SpotOrderPort> none = mock(ObjectProvider.class);
        StepRunner r = new StepRunner(none, steps, recorder, events, new FixedDomainClock(Instant.EPOCH),
                mock(PlatformTransactionManager.class), Duration.ZERO, 1);
        assertThrows(IllegalStateException.class, () -> r.send(ctx()));
        assertEquals(StepStatus.PLANNED, step.getStatus());
        assertNull(step.getSubmittedAt());
    }
}
