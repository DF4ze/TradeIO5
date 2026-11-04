package fr.ses10doigts.tradeIO5.service.execution;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.repository.execution.ExecutionControlRepository;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService.Limits;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Plafonds et seuils Fee Test : lecture avec repli, mise à jour validée")
class ExecutionControlLimitsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private ExecutionControlRepository repository;
    private ExecutionControlService service;
    private ExecutionControl row;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    void setUp() {
        repository = mock(ExecutionControlRepository.class);
        service = new ExecutionControlService(repository, () -> NOW);
        row = ExecutionControl.initial(NOW);
        when(repository.findById(ExecutionControl.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repository.save(any(ExecutionControl.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("Colonnes nulles => valeurs codées de l'initializer")
    void nullColumnsFallBack() {
        Limits l = Limits.of(row);
        assertEquals(0, d("30").compareTo(l.absoluteMaxOrderUsd()));
        assertEquals(0, d("100").compareTo(l.absoluteMaxDayUsd()));
        assertEquals(0, d("0.2").compareTo(l.feeWarnPct()));
        assertEquals(0, d("0.8").compareTo(l.feeRedPct()));
    }

    @Test
    @DisplayName("Mise à jour valide : persistée et lue aussitôt par le Fee Test")
    void updateIsReadByFeeTest() {
        service.updateLimits(new Limits(d("1.5"), d("3"), d("60"), d("200"), d("0.3"), d("1")), "clem");

        assertEquals(0, d("60").compareTo(row.getAbsoluteMaxOrderUsd()));
        assertEquals("clem", row.getUpdatedBy());
        FeeTest feeTest = new FeeTest(service);
        assertEquals(0, d("0.3").compareTo(feeTest.warnPct()));
        assertEquals(0, d("1").compareTo(feeTest.redPct()));
    }

    @Test
    @DisplayName("Valeurs invalides => refus, rien d'écrit")
    void invalidRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.updateLimits(new Limits(d("0"), d("2"), d("30"), d("100"), d("0.2"), d("0.8")), "x"));
        assertThrows(IllegalArgumentException.class, () -> service.updateLimits(new Limits(d("1"), d("2"), d("30"), d("20"), d("0.2"), d("0.8")), "x"));
        assertThrows(IllegalArgumentException.class, () -> service.updateLimits(new Limits(d("1"), d("2"), d("30"), d("100"), d("0.9"), d("0.8")), "x"));
        assertThrows(IllegalArgumentException.class, () -> service.updateLimits(new Limits(d("1"), d("2"), d("30"), d("100"), d("0.2"), d("101")), "x"));
        assertThrows(IllegalArgumentException.class, () -> service.updateLimits(new Limits(d("1"), d("2"), null, d("100"), d("0.2"), d("0.8")), "x"));
        verify(repository, never()).save(any());
    }
}
