package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminStateDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderExecutor;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("État admin : mode, verrous, kill switch, plafonds absolus lus dans ExecutionDefaults, exchanges câblés")
class AdminStateServiceTest {

    @Test
    @DisplayName("Défauts (DRY_RUN, kill switch engagé, aucun port) puis LIVE déverrouillé avec OKX")
    void state() {
        ExecutionControlService control = mock(ExecutionControlService.class);
        ExecutionControl c = ExecutionControl.initial(Instant.parse("2026-10-11T10:00:00Z"));
        when(control.current()).thenReturn(c);
        SecretCipher cipher = mock(SecretCipher.class);
        OrderExecutor executor = mock(OrderExecutor.class);

        AdminStateDto locked = new AdminStateService(new ExecutionSettings(), control, cipher, executor).state();
        assertEquals("DRY_RUN", locked.mode());
        assertFalse(locked.liveUnlocked());
        assertFalse(locked.liveExecution());
        assertFalse(locked.masterKeyConfigured());
        assertTrue(locked.killSwitch());
        assertEquals("initializer", locked.killSwitchUpdatedBy());
        assertEquals(ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD, locked.absoluteMaxOrderUsd());
        assertEquals(ExecutionDefaults.ABSOLUTE_MAX_DAY_USD, locked.absoluteMaxDayUsd());
        assertEquals(ExecutionDefaults.DEFAULT_MAX_ORDER_MULTIPLIER, locked.maxOrderMultiplier());
        assertTrue(locked.orderProviders().isEmpty());

        when(cipher.isConfigured()).thenReturn(true);
        when(executor.orderProviders()).thenReturn(List.of(WebProviderCode.OKX));
        ExecutionSettings live = new ExecutionSettings("LIVE", ExecutionDefaults.PLAN_TTL, ExecutionDefaults.SLIPPAGE_TOLERANCE_PCT, true, true);
        AdminStateDto open = new AdminStateService(live, control, cipher, executor).state();
        assertEquals("LIVE", open.mode());
        assertTrue(open.liveUnlocked() && open.liveExecution() && open.masterKeyConfigured());
        assertEquals(List.of("OKX"), open.orderProviders());
    }
}
