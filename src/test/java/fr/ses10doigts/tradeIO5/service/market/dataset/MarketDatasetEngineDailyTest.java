package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.dataset.time.TimeFrameConverter;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Chemin D1 natif {@link MarketDatasetEngine#getDailyDatasetForAsset} : source du lien, fallback bruyant, création à la volée. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Market Dataset - chemin D1 natif par source")
class MarketDatasetEngineDailyTest {

    private static final Instant END = Instant.parse("2026-10-12T23:55:00Z");
    private static final int LOOKBACK = 720;

    @Mock MarketDatasetCache cache;
    @Mock MarketDatasetManager manager;
    @Mock MarketDataProviderRegistry providerRegistry;
    @Mock MarketDataApiClient binanceClient;
    @Mock MarketDataApiClient krakenClient;
    @Mock MarketDataApiClient okxClient;
    @Mock AssetProviderRepository assetProviderRepository;

    MarketDatasetEngine engine;
    Asset paxg = mock(Asset.class);

    @BeforeEach
    void setup() {
        engine = new MarketDatasetEngine(cache, manager, providerRegistry, new TimeFrameConverter(),
                binanceClient, krakenClient, okxClient, assetProviderRepository);
    }

    private AssetProvider row(MarketDataSource source, String symbol, int priority, boolean enabled) {
        return AssetProvider.builder().id(priority + 1L).asset(paxg).source(source).providerSymbol(symbol)
                .priority(priority).enabled(enabled).build();
    }

    private static List<MarketData> candles(String pair) {
        return List.of(MarketData.builder().pair(pair).timeFrame(TimeFrame.D1)
                .timestamp(Instant.parse("2026-10-12T00:00:00Z"))
                .open(BigDecimal.ONE).high(BigDecimal.TEN).low(BigDecimal.ONE).close(BigDecimal.TEN)
                .volume(BigDecimal.ONE).build());
    }

    private void defaultRows() {
        when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("PAXG")).thenReturn(List.of(
                row(MarketDataSource.BINANCE, "PAXGUSDT", 0, true),
                row(MarketDataSource.KRAKEN, "PAXGUSD", 1, true),
                row(MarketDataSource.OKX, "PAXG-USDT", 2, true)));
    }

    @Test
    @DisplayName("Source demandée disponible : servie seule, 720 j demandés en D1, pas de substitution")
    void preferredSourceServedAlone() {
        defaultRows();
        when(krakenClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenReturn(candles("PAXGUSD"));

        DailyDataset result = engine.getDailyDatasetForAsset("PAXG", LOOKBACK, END, MarketDataSource.KRAKEN);

        assertEquals(MarketDataSource.KRAKEN, result.source());
        assertEquals("PAXGUSD", result.providerSymbol());
        assertFalse(result.substituted());
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(krakenClient).getCandles(eq("PAXGUSD"), eq(TimeFrame.D1), since.capture(), eq(END), eq(LOOKBACK));
        assertEquals(Instant.parse("2024-10-23T00:00:00Z"), since.getValue()); // 719 j avant 2026-10-12
        verify(binanceClient, never()).getCandles(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Sans source : défauts par priorité (Binance d'abord), pas de substitution")
    void noPreferredUsesDefaults() {
        defaultRows();
        when(binanceClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenReturn(candles("PAXGUSDT"));

        DailyDataset result = engine.getDailyDatasetForAsset("PAXG", LOOKBACK, END, null);

        assertEquals(MarketDataSource.BINANCE, result.source());
        assertFalse(result.substituted());
    }

    @Test
    @DisplayName("Source demandée en échec : fallback sur la suivante par priorité, marqué substitué")
    void preferredFailsFallsBackSubstituted() {
        defaultRows();
        when(krakenClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt()))
                .thenThrow(new ProviderUnavailableException(MarketDataSource.KRAKEN, "PAXGUSD", "down", null));
        when(binanceClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenReturn(candles("PAXGUSDT"));

        DailyDataset result = engine.getDailyDatasetForAsset("PAXG", LOOKBACK, END, MarketDataSource.KRAKEN);

        assertEquals(MarketDataSource.BINANCE, result.source());
        assertTrue(result.substituted());
    }

    @Test
    @DisplayName("Source demandée qui renvoie 0 bougie : traitée comme un échec (fallback substitué)")
    void preferredEmptyIsFailure() {
        defaultRows();
        when(krakenClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenReturn(List.of());
        when(binanceClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenReturn(candles("PAXGUSDT"));

        DailyDataset result = engine.getDailyDatasetForAsset("PAXG", LOOKBACK, END, MarketDataSource.KRAKEN);

        assertTrue(result.substituted());
    }

    @Test
    @DisplayName("Ligne asset_provider absente pour la source du lien : créée à la volée (exchange de confiance), priorité en dernier")
    void missingRowCreatedOnTheFly() {
        when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("PAXG")).thenReturn(List.of(
                row(MarketDataSource.BINANCE, "PAXGUSDT", 0, true),
                row(MarketDataSource.KRAKEN, "PAXGUSD", 1, true)));
        when(assetProviderRepository.save(any(AssetProvider.class))).thenAnswer(inv -> inv.getArgument(0));
        when(okxClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenReturn(candles("PAXG-USDT"));

        DailyDataset result = engine.getDailyDatasetForAsset("PAXG", LOOKBACK, END, MarketDataSource.OKX);

        ArgumentCaptor<AssetProvider> saved = ArgumentCaptor.forClass(AssetProvider.class);
        verify(assetProviderRepository).save(saved.capture());
        assertEquals(MarketDataSource.OKX, saved.getValue().getSource());
        assertEquals("PAXG-USDT", saved.getValue().getProviderSymbol());
        assertEquals(2, saved.getValue().getPriority());
        assertTrue(saved.getValue().isEnabled());
        assertEquals(MarketDataSource.OKX, result.source());
        assertFalse(result.substituted());
    }

    @Test
    @DisplayName("Source demandée désactivée (kill switch) : respectée, rien n'est créé, fallback substitué")
    void disabledPreferredRespected() {
        when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("PAXG")).thenReturn(List.of(
                row(MarketDataSource.BINANCE, "PAXGUSDT", 0, true),
                row(MarketDataSource.KRAKEN, "PAXGUSD", 1, false)));
        when(binanceClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenReturn(candles("PAXGUSDT"));

        DailyDataset result = engine.getDailyDatasetForAsset("PAXG", LOOKBACK, END, MarketDataSource.KRAKEN);

        verify(krakenClient, never()).getCandles(anyString(), any(), any(), any(), anyInt());
        verify(assetProviderRepository, never()).save(any(AssetProvider.class));
        assertTrue(result.substituted());
    }

    @Test
    @DisplayName("Tous les providers en échec : NoProviderAvailableException")
    void allFailThrows() {
        defaultRows();
        ProviderUnavailableException down = new ProviderUnavailableException(MarketDataSource.BINANCE, "PAXGUSDT", "down", null);
        when(binanceClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenThrow(down);
        when(krakenClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenThrow(down);
        when(okxClient.getCandles(anyString(), eq(TimeFrame.D1), any(), any(), anyInt())).thenThrow(down);

        assertThrows(NoProviderAvailableException.class,
                () -> engine.getDailyDatasetForAsset("PAXG", LOOKBACK, END, MarketDataSource.KRAKEN));
    }
}
