package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.RouteValidationDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.RouteValidationRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderTestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Testeur d'ordre : validation d'une route jambe par jambe (aucun envoi)")
class AdminOrderTestServiceRouteTest {

    private final OrderTestService orderTests = mock(OrderTestService.class);
    private final AdminOrderTestService service = new AdminOrderTestService(orderTests, mock(AdminActionLogService.class),
            mock(ExecutionControlService.class));

    private static OrderTestRequest leg(String instId) {
        return new OrderTestRequest(1L, WebProviderCode.KRAKEN, instId, StepSide.BUY, new BigDecimal("0.001"), null, new BigDecimal("1"));
    }

    private static ValidationReportDto report(boolean accepted, String code, String message) {
        return new ValidationReportDto(accepted, null, List.of(), null, code, message, null, null);
    }

    @Test
    @DisplayName("Deux jambes acceptées : allAccepted, aucune note")
    void bothAccepted() {
        when(orderTests.validate(any())).thenReturn(report(true, null, null));
        RouteValidationDto dto = service.validateRoute(new RouteValidationRequest(List.of(leg("BTC-USDC"), leg("PAXG-BTC"))), "admin");
        assertTrue(dto.allAccepted());
        assertEquals(2, dto.legs().size());
        assertNull(dto.note());
    }

    @Test
    @DisplayName("Jambe 2 refusée faute de fonds : note explicite, jamais d'envoi")
    void leg2InsufficientFunds() {
        when(orderTests.validate(any())).thenReturn(report(true, null, null), report(false, "EOrder:Insufficient funds", "refus"));
        RouteValidationDto dto = service.validateRoute(new RouteValidationRequest(List.of(leg("BTC-USDC"), leg("PAXG-BTC"))), "admin");
        assertFalse(dto.allAccepted());
        assertNotNull(dto.note());
        verify(orderTests, never()).sendReal(any(), any());
    }

    @Test
    @DisplayName("Route vide => refus")
    void emptyRoute() {
        assertThrows(IllegalArgumentException.class, () -> service.validateRoute(new RouteValidationRequest(List.of()), "admin"));
    }
}
