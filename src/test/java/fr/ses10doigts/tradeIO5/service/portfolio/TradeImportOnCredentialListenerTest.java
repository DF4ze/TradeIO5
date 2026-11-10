package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialStoredEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class TradeImportOnCredentialListenerTest {

    private final User user = User.builder().id(1L).username("clem").build();
    private final ExchangeTradeImportService importService = mock(ExchangeTradeImportService.class);
    private final TradeImportOnCredentialListener listener = new TradeImportOnCredentialListener(importService);

    @Test
    void newKeyTriggersForcedImportOfAllUserWallets() {
        when(importService.sync(user, null, true)).thenReturn(new ExchangeTradeImportService.SyncResult(3, 1, 2, List.of()));
        listener.onReadCredentialStored(new ReadCredentialStoredEvent(user, WebProviderCode.REVOLUT_X));
        verify(importService).sync(user, null, true);
    }

    @Test
    void failureIsSwallowed() {
        when(importService.sync(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("boom"));
        assertDoesNotThrow(() -> listener.onReadCredentialStored(new ReadCredentialStoredEvent(user, WebProviderCode.OKX)));
    }
}
