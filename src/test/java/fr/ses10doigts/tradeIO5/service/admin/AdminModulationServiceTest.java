package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService.Params;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminModulationService")
class AdminModulationServiceTest {

    private final RainbowModulationControlService control = mock(RainbowModulationControlService.class);
    private final AdminActionLogService journal = mock(AdminActionLogService.class);
    private final AdminModulationService service = new AdminModulationService(control, journal);

    @Test
    @DisplayName("PUT valide : ligne MODULATION / rainbow-modulation avec valeurs avant et après")
    @SuppressWarnings("unchecked")
    void journalsBeforeAndAfter() {
        RainbowModulationControl before = RainbowModulationControl.defaults(Instant.EPOCH, null);
        RainbowModulationControl after = RainbowModulationControl.defaults(Instant.EPOCH, null);
        after.setAutonomyDays(30);
        when(control.current()).thenReturn(before);
        when(control.update(any(), eq("root"))).thenReturn(after);

        service.update(Params.of(after), "root");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("rainbow-modulation"), payload.capture(),
                eq(AdminActionOutcome.OK));
        assertEquals(90, ((Map<String, Object>) payload.getValue().get("before")).get("autonomyDays"));
        assertEquals(30, ((Map<String, Object>) payload.getValue().get("after")).get("autonomyDays"));
    }

    @Test
    @DisplayName("PUT invalide : journal REFUSED sans valeur, exception propagée")
    void refusedIsJournaled() {
        when(control.current()).thenReturn(RainbowModulationControl.defaults(Instant.EPOCH, null));
        when(control.update(any(), any())).thenThrow(new IllegalArgumentException("bad"));
        Params bad = new Params(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 1);

        assertThrows(IllegalArgumentException.class, () -> service.update(bad, "root"));

        verify(journal).record(eq("root"), eq(AdminAction.MODULATION), eq("rainbow-modulation"), any(),
                eq(AdminActionOutcome.REFUSED));
    }
}
