package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.admin.OrderTestRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.ValidationReportDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialResolver;
import fr.ses10doigts.tradeIO5.service.execution.exchange.ExchangeUnavailableException;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderRequest;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderValidator;
import fr.ses10doigts.tradeIO5.service.execution.exchange.ValidationReport;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderTestService : verrous globaux, contrôles locaux, validation sans envoi")
class OrderTestServiceTest {

    private static final BigDecimal TEN = BigDecimal.TEN;

    private ExecutionControlService control;
    private ExecutionControl controlState;
    private SecretCipher cipher;
    private SpotOrderPort port;
    private SpotOrderValidator validator;
    private TradeCredentialResolver credentials;
    private InstrumentCatalog catalog;
    private WebProvider kraken;

    @BeforeEach
    void setUp() {
        control = mock(ExecutionControlService.class);
        controlState = mock(ExecutionControl.class);
        when(control.current()).thenReturn(controlState);
        cipher = mock(SecretCipher.class);
        when(cipher.isConfigured()).thenReturn(true);
        port = mock(SpotOrderPort.class);
        when(port.provider()).thenReturn(WebProviderCode.KRAKEN);
        validator = mock(SpotOrderValidator.class);
        when(validator.provider()).thenReturn(WebProviderCode.KRAKEN);
        credentials = mock(TradeCredentialResolver.class);
        when(credentials.resolve(any(), any())).thenReturn(Optional.of(ApiCredential.builder().apiKey("k-secret").secretKey("s-secret").build()));
        kraken = WebProvider.builder().code(WebProviderCode.KRAKEN).build();
        catalog = mock(InstrumentCatalog.class);
        when(catalog.allInstruments(kraken)).thenReturn(List.of(instrument("BTC-USDC", "live", "0.00005", "0.00000001", "0.1", "0.5"),
                instrument("BTC-EUR", "live", "0.00005", "0.00000001", "0.1", "0.5")));
        when(validator.validate(any(), any())).thenReturn(ValidationReport.accepted("ok", "{\"error\":[]}"));
    }

    private ExchangeInstrument instrument(String id, String state, String min, String lot, String tick, String costMin) {
        String[] bq = id.split("-");
        return ExchangeInstrument.builder().provider(WebProviderCode.KRAKEN).instId(id).base(bq[0]).quote(bq[1]).state(state)
                .minSz(new BigDecimal(min)).lotSz(new BigDecimal(lot)).tickSz(new BigDecimal(tick)).costMin(new BigDecimal(costMin))
                .nativePair("XBT" + bq[1]).feeCurrency(bq[1]).build();
    }

    private OrderTestService service(ExecutionSettings settings) {
        UserRepository users = mock(UserRepository.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.findByCode(WebProviderCode.KRAKEN)).thenReturn(Optional.of(kraken));
        return new OrderTestService(settings, control, cipher, new SpotOrderRouter(List.of(port)), List.of(validator), credentials, catalog,
                providers, users, new FixedDomainClock(Instant.parse("2026-10-10T00:00:00Z")), "EUR,USD");
    }

    private static ExecutionSettings live() {
        return new ExecutionSettings("LIVE", Duration.ofMinutes(5), new BigDecimal("0.1"), true, true);
    }

    private static OrderTestRequest request(String instId, String sz, String px) {
        return new OrderTestRequest(1L, WebProviderCode.KRAKEN, instId, StepSide.BUY, sz == null ? null : new BigDecimal(sz), null,
                px == null ? null : new BigDecimal(px));
    }

    private static boolean check(ValidationReportDto dto, String name) {
        return dto.localChecks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().ok();
    }

    @Test
    @DisplayName("Mode non LIVE => blockedBy MODE_NOT_LIVE, contrôles locaux présents, aucun appel exchange")
    void notLive() {
        ValidationReportDto dto = service(new ExecutionSettings()).validate(request("BTC-USDC", "0.0001", "60000"));
        assertFalse(dto.accepted());
        assertEquals("MODE_NOT_LIVE", dto.blockedBy());
        assertTrue(check(dto, "pair_known"));
        verify(validator, never()).validate(any(), any());
        verify(credentials, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("Clé maître absente / kill switch / clé Trade absente => blocage explicite, aucun appel exchange")
    void locks() {
        when(cipher.isConfigured()).thenReturn(false);
        assertEquals("MASTER_KEY_MISSING", service(live()).validate(request("BTC-USDC", "0.0001", "60000")).blockedBy());
        when(cipher.isConfigured()).thenReturn(true);
        when(controlState.isKillSwitch()).thenReturn(true);
        assertEquals("KILL_SWITCH", service(live()).validate(request("BTC-USDC", "0.0001", "60000")).blockedBy());
        when(controlState.isKillSwitch()).thenReturn(false);
        when(credentials.resolve(any(), any())).thenReturn(Optional.empty());
        assertEquals("CREDENTIAL_INVALID", service(live()).validate(request("BTC-USDC", "0.0001", "60000")).blockedBy());
        verify(validator, never()).validate(any(), any());
    }

    @Test
    @DisplayName("Verrous levés : validation envoyée à l'exchange, arrondis lot / tick, jamais submit")
    void accepted() {
        ValidationReportDto dto = service(live()).validate(request("BTC-USDC", "0.000123456789", "60000.07"));
        assertTrue(dto.accepted());
        assertNull(dto.blockedBy());
        ArgumentCaptor<OrderRequest> sent = ArgumentCaptor.forClass(OrderRequest.class);
        verify(validator, times(1)).validate(any(), sent.capture());
        assertEquals(0, new BigDecimal("0.00012345").compareTo(sent.getValue().sz()));
        assertEquals(0, new BigDecimal("60000.0").compareTo(sent.getValue().px()));
        assertEquals(StepSide.BUY, sent.getValue().side());
        assertTrue(sent.getValue().clOrdId().startsWith("t5test"));
        verify(port, never()).submit(any(), any());
    }

    @Test
    @DisplayName("Minimum de taille ou coût minimum en échec : signalé, exchange interrogé, accepted faux")
    void localFailure() {
        ValidationReportDto dto = service(live()).validate(request("BTC-USDC", "0.00001", "30000"));
        assertFalse(dto.accepted());
        assertFalse(check(dto, "min_size"));
        assertFalse(check(dto, "cost_min"));
        verify(validator, times(1)).validate(any(), any());
    }

    @Test
    @DisplayName("Plafond absolu dépassé => cap_absolute_order en échec ; paire inconnue ou fiat => aucun appel exchange")
    void capAndStructural() {
        ValidationReportDto cap = service(live()).validate(request("BTC-USDC", "0.001", "60000"));
        assertFalse(check(cap, "cap_absolute_order"));
        assertFalse(cap.accepted());
        ValidationReportDto unknown = service(live()).validate(request("DOGE-USDC", "1", "1"));
        assertFalse(check(unknown, "pair_known"));
        ValidationReportDto fiat = service(live()).validate(request("BTC-EUR", "0.0001", "60000"));
        assertFalse(check(fiat, "no_fiat"));
        verify(validator, times(1)).validate(any(), any());
    }

    @Test
    @DisplayName("Refus de l'exchange / exchange indisponible => rapport refusé, pas d'exception")
    void exchangeRefusal() {
        when(validator.validate(any(), any())).thenReturn(ValidationReport.refused("EOrder:Order minimum not met", "min", null));
        ValidationReportDto dto = service(live()).validate(request("BTC-USDC", "0.0001", "60000"));
        assertFalse(dto.accepted());
        assertEquals("EOrder:Order minimum not met", dto.exchangeCode());
        when(validator.validate(any(), any())).thenThrow(new ExchangeUnavailableException("down"));
        assertEquals("EXCHANGE_UNAVAILABLE", service(live()).validate(request("BTC-USDC", "0.0001", "60000")).exchangeCode());
    }

    @Test
    @DisplayName("Demande incohérente => IllegalArgumentException ; DTO sans champ secret")
    void invalidAndNoSecret() {
        assertThrows(IllegalArgumentException.class, () -> service(live()).validate(request("BTC-USDC", null, "60000")));
        assertThrows(IllegalArgumentException.class, () -> service(live()).validate(
                new OrderTestRequest(1L, WebProviderCode.KRAKEN, "BTC-USDC", StepSide.BUY, TEN, TEN, TEN)));
        ValidationReportDto dto = service(live()).validate(request("BTC-USDC", "0.0001", "60000"));
        assertFalse(dto.toString().contains("k-secret") || dto.toString().contains("s-secret"));
        for (var c : ValidationReportDto.class.getRecordComponents()) {
            assertFalse(c.getName().toLowerCase().matches(".*(secret|key|passphrase|signature|nonce).*"), c.getName());
        }
    }
}
