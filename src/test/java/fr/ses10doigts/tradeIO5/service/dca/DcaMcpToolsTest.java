package fr.ses10doigts.tradeIO5.service.dca;

import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.AssetSymbolValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. {@code TreeAnalysisMcpToolsTest} : même garde-fou {@link AssetSymbolValidator} branché côté
 * {@code calculate_dca} — le symbole doit être l'Asset nu (ex: {@code "BTC"}), jamais une paire
 * d'exchange (ex: {@code "BTCUSDT"}), la traduction se faisant désormais en interne dans
 * {@link DcaCalculatorService} (cf. {@code DcaCalculatorServiceTest}).
 */
@DisplayName("DcaMcpTools — validation du symbole (asset nu, pas une paire d'exchange)")
@ExtendWith(MockitoExtension.class)
class DcaMcpToolsTest {

    @Mock
    private DcaCalculatorService dcaCalculatorService;

    @Mock
    private AssetSymbolValidator assetSymbolValidator;

    private DcaMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new DcaMcpTools(dcaCalculatorService, assetSymbolValidator);
    }

    @Test
    @DisplayName("calculate_dca : symbole rejeté par AssetSymbolValidator → JSON d'erreur, service jamais appelé")
    void calculateDca_unknownSymbol_returnsErrorAndNeverCallsService() {
        doThrow(new IllegalArgumentException("Symbole d'actif inconnu : 'BTCUSDT'. Symboles connus : [BTC, ETH]"))
                .when(assetSymbolValidator).requireKnownAsset("BTCUSDT");

        String json = tools.calculateDca(
                "BTCUSDT", "2026-01-01", "2026-01-10", TimeFrame.D1, 0, 100.0, null, null);

        assertTrue(json.contains("\"error\":true"));
        assertTrue(json.contains("Symbole d'actif inconnu"));
        verify(dcaCalculatorService, never()).calculate(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("calculate_dca : symbole nu connu → validé puis délégué au service")
    void calculateDca_knownSymbol_delegatesToService() {
        doNothing().when(assetSymbolValidator).requireKnownAsset("BTC");
        when(dcaCalculatorService.calculate(eq("BTC"), any(), any(), eq(TimeFrame.D1), eq(0), any(), any(), any()))
                .thenReturn(DcaResult.builder()
                        .symbol("BTC")
                        .source(MarketDataSource.BINANCE)
                        .frequency(TimeFrame.D1)
                        .purchaseHourUtc(0)
                        .firstOccurrence(Instant.parse("2026-01-01T00:00:00Z"))
                        .lastOccurrence(Instant.parse("2026-01-10T00:00:00Z"))
                        .occurrenceCount(10)
                        .missingCount(0)
                        .totalInvested(BigDecimal.valueOf(1000))
                        .totalFees(BigDecimal.ZERO)
                        .totalQuantity(BigDecimal.TEN)
                        .avgPrice(BigDecimal.valueOf(100))
                        .currentPrice(BigDecimal.valueOf(110))
                        .currentValue(BigDecimal.valueOf(1100))
                        .pnl(BigDecimal.valueOf(100))
                        .pnlPercent(BigDecimal.TEN)
                        .occurrences(List.of())
                        .build());

        String json = tools.calculateDca(
                "BTC", "2026-01-01", "2026-01-10", TimeFrame.D1, 0, 100.0, null, null);

        assertFalse(json.contains("\"error\":true"));
        assertTrue(json.contains("\"symbol\":\"BTC\""));
        verify(assetSymbolValidator).requireKnownAsset("BTC");
        verify(dcaCalculatorService).calculate(eq("BTC"), any(), any(), eq(TimeFrame.D1), eq(0), any(), any(), any());
    }
}
