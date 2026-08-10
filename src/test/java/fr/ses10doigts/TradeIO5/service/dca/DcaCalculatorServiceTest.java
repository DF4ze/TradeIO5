package fr.ses10doigts.tradeIO5.service.dca;

import fr.ses10doigts.tradeIO5.exceptions.DcaException;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cf. docs/etude-fallback-multi-provider-marketdata.md §3 (étape 8b) : {@code DcaCalculatorService}
 * ne bypass pas la boucle de fallback complète de {@code MarketDatasetEngine} (bypass volontaire
 * documenté dans sa javadoc), mais résout {@code effectiveSource}/{@code maxHorizonDays}/
 * {@code providerSymbol} via {@code asset_provider} — y compris la traduction du symbole nu de
 * l'Asset (ex: {@code "BTC"}) vers la paire native de l'exchange (ex: {@code "XXBTZUSD"} chez
 * Kraken), qui manquait jusqu'ici : le client recevait directement le symbole passé par
 * l'appelant, jamais traduit. Repli sur l'ancien comportement (BINANCE /
 * {@code NON_BINANCE_MAX_HORIZON_DAYS} / symbole tel quel) si l'asset n'est pas encore migré.
 */
@DisplayName("DcaCalculatorService")
@ExtendWith(MockitoExtension.class)
class DcaCalculatorServiceTest {

    @Mock
    private MarketDataApiClient binanceClient;

    @Mock
    private MarketDataApiClient krakenClient;

    @Mock
    private MarketDataApiClient okxClient;

    @Mock
    private AssetProviderRepository assetProviderRepository;

    private DcaCalculatorService service;

    /** Une seule bougie suffit : c'est aussi bien la "current price" que le seul point du calendrier résolu. */
    private static final MarketData SAMPLE_CANDLE = MarketData.builder()
            .timeFrame(TimeFrame.H1)
            .pair("SAMPLE")
            .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
            .open(BigDecimal.TEN)
            .high(BigDecimal.TEN)
            .low(BigDecimal.TEN)
            .close(BigDecimal.TEN)
            .volume(BigDecimal.ZERO)
            .build();

    @BeforeEach
    void setUp() {
        FixedDomainClock clock = new FixedDomainClock(Instant.parse("2026-02-01T00:00:00Z"));
        service = new DcaCalculatorService(binanceClient, krakenClient, okxClient, clock, assetProviderRepository);
    }

    @Test
    @DisplayName("source == null + asset avec favori Kraken en base → effectiveSource résolu à KRAKEN, "
            + "le client reçoit le providerSymbol traduit (XXBTZUSD), pas le symbole Asset (BTC)")
    void sourceNull_favoriteKrakenInDb_resolvesToKrakenWithTranslatedSymbol() {
        AssetProvider krakenFavorite = AssetProvider.builder()
                .source(MarketDataSource.KRAKEN)
                .providerSymbol("XXBTZUSD")
                .priority(0)
                .maxHorizonDays(25)
                .build();
        when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("BTC"))
                .thenReturn(List.of(krakenFavorite));
        when(assetProviderRepository.findByAsset_SymbolAndSource("BTC", MarketDataSource.KRAKEN))
                .thenReturn(Optional.of(krakenFavorite));
        when(krakenClient.getCandles(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        DcaResult result = service.calculate(
                "BTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3),
                TimeFrame.D1, 0, BigDecimal.valueOf(100), null, null);

        assertEquals(MarketDataSource.KRAKEN, result.getSource());
        // Le résultat affiche le symbole Asset nu, pas la paire native de l'exchange.
        assertEquals("BTC", result.getSymbol());
        verify(krakenClient, atLeastOnce()).getCandles(eq("XXBTZUSD"), any(), any(), any(), anyInt());
        verify(krakenClient, never()).getCandles(eq("BTC"), any(), any(), any(), anyInt());
        verifyNoInteractions(binanceClient, okxClient);
    }

    @Test
    @DisplayName("source == null + asset absent d'asset_provider → repli sur BINANCE ET sur le symbole brut "
            + "pour le client (comportement identique à avant cette étape)")
    void sourceNull_assetAbsentFromAssetProvider_fallsBackToBinanceAndRawSymbol() {
        when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("UNKNOWN"))
                .thenReturn(List.of());
        when(binanceClient.getCandles(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        DcaResult result = service.calculate(
                "UNKNOWN", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3),
                TimeFrame.D1, 0, BigDecimal.valueOf(100), null, null);

        assertEquals(MarketDataSource.BINANCE, result.getSource());
        verify(binanceClient, atLeastOnce()).getCandles(eq("UNKNOWN"), any(), any(), any(), anyInt());
        verifyNoInteractions(krakenClient, okxClient);
    }

    @Test
    @DisplayName("Horizon dépassé avec un maxHorizonDays venant de la DB différent de la constante → "
            + "le message DcaException utilise la valeur DB, pas NON_BINANCE_MAX_HORIZON_DAYS (25)")
    void horizonExceeded_exceptionMessageUsesDbValue_notTheConstant() {
        AssetProvider krakenFavorite = AssetProvider.builder()
                .source(MarketDataSource.KRAKEN)
                .providerSymbol("XETHZUSD")
                .priority(0)
                .maxHorizonDays(10)
                .build();
        when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("ETH"))
                .thenReturn(List.of(krakenFavorite));
        when(assetProviderRepository.findByAsset_SymbolAndSource("ETH", MarketDataSource.KRAKEN))
                .thenReturn(Optional.of(krakenFavorite));

        // 19 jours d'écart : dépasse la valeur DB (10j) mais serait resté sous l'ancienne
        // constante en dur (25j) si le fallback ne fonctionnait pas.
        DcaException ex = assertThrows(DcaException.class, () -> service.calculate(
                "ETH", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20),
                TimeFrame.D1, 0, BigDecimal.valueOf(100), null, null));

        assertTrue(ex.getMessage().contains("~10"), "Le message doit citer la valeur DB (10) : " + ex.getMessage());
        assertFalse(ex.getMessage().contains("~25"), "Le message ne doit pas citer la constante (25) : " + ex.getMessage());
        verifyNoInteractions(binanceClient, krakenClient, okxClient);
    }

    @Test
    @DisplayName("source fournie explicitement → resolveEffectiveSource ne consulte pas asset_provider, "
            + "mais la traduction providerSymbol le consulte quand même (findByAsset_SymbolAndSource)")
    void sourceProvidedExplicitly_stillResolvesProviderSymbol() {
        AssetProvider binanceRow = AssetProvider.builder()
                .source(MarketDataSource.BINANCE)
                .providerSymbol("BTCUSDT")
                .priority(0)
                .build();
        when(assetProviderRepository.findByAsset_SymbolAndSource("BTC", MarketDataSource.BINANCE))
                .thenReturn(Optional.of(binanceRow));
        when(binanceClient.getCandles(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        DcaResult result = service.calculate(
                "BTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3),
                TimeFrame.D1, 0, BigDecimal.valueOf(100), null, MarketDataSource.BINANCE);

        assertEquals(MarketDataSource.BINANCE, result.getSource());
        verify(assetProviderRepository, never()).findByAsset_SymbolOrderByPriorityAsc(any());
        verify(assetProviderRepository).findByAsset_SymbolAndSource("BTC", MarketDataSource.BINANCE);
        verify(binanceClient, atLeastOnce()).getCandles(eq("BTCUSDT"), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("source explicite + asset absent d'asset_provider → repli sur le symbole brut pour le client")
    void sourceProvidedExplicitly_assetAbsent_fallsBackToRawSymbolForClient() {
        when(assetProviderRepository.findByAsset_SymbolAndSource("XYZ", MarketDataSource.OKX))
                .thenReturn(Optional.empty());
        when(okxClient.getCandles(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        DcaResult result = service.calculate(
                "XYZ", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3),
                TimeFrame.D1, 0, BigDecimal.valueOf(100), null, MarketDataSource.OKX);

        assertEquals(MarketDataSource.OKX, result.getSource());
        verify(okxClient, atLeastOnce()).getCandles(eq("XYZ"), any(), any(), any(), anyInt());
    }
}
