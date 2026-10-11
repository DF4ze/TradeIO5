package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.execution.exchange.FakeSpotExchange;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SpotOrderRouter : un port par provider")
class SpotOrderRouterTest {

    private static SpotOrderPort port(WebProviderCode code) {
        SpotOrderPort p = mock(SpotOrderPort.class);
        when(p.provider()).thenReturn(code);
        return p;
    }

    @Test
    @DisplayName("Chaque provider obtient son port ; provider sans port => vide ; liste triée des providers actifs")
    void routes() {
        SpotOrderPort okx = new FakeSpotExchange();
        SpotOrderPort kraken = port(WebProviderCode.KRAKEN);
        SpotOrderRouter router = new SpotOrderRouter(List.of(kraken, okx));
        assertSame(okx, router.forProvider(WebProviderCode.OKX).orElseThrow());
        assertSame(kraken, router.forProvider(WebProviderCode.KRAKEN).orElseThrow());
        assertTrue(router.forProvider(WebProviderCode.BINANCE).isEmpty());
        assertTrue(router.forProvider(null).isEmpty());
        assertEquals(List.of(WebProviderCode.KRAKEN, WebProviderCode.OKX), router.providers().stream().sorted().toList());
    }

    @Test
    @DisplayName("Aucun port => aucun provider ; deux ports pour le même provider => échec")
    void emptyAndDuplicate() {
        assertTrue(new SpotOrderRouter(List.<SpotOrderPort>of()).providers().isEmpty());
        assertThrows(IllegalStateException.class, () -> new SpotOrderRouter(List.of(new FakeSpotExchange(), new FakeSpotExchange())));
    }
}
