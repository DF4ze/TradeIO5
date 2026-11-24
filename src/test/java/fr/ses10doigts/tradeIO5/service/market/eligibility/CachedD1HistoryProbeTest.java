package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.market.dataset.NoProviderAvailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CachedD1HistoryProbe : jours D1 closes via le pipeline à cache DB")
class CachedD1HistoryProbeTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Instant LAST_CLOSED = Instant.parse("2026-11-23T00:00:00Z");

    private final MarketDatasetEngine engine = mock(MarketDatasetEngine.class);
    private final CachedD1HistoryProbe probe = new CachedD1HistoryProbe(engine, new FixedDomainClock(NOW));

    private static DailyDataset daily(int candles) {
        List<MarketData> data = new ArrayList<>();
        for (int i = 0; i < candles; i++) {
            data.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(LAST_CLOSED.minus(i, ChronoUnit.DAYS)).build());
        }
        return new DailyDataset(MarketDataset.builder().marketDatas(data).size(candles).build(), MarketDataSource.KRAKEN, "SOLUSD", false);
    }

    @Test
    @DisplayName("Lit jusqu'à la veille (dernière bougie close) et plafonne à 120 jours")
    void readsUpToLastClosedDay() {
        when(engine.getDailyDatasetOnSource(eq("SOL"), eq(MarketDataSource.KRAKEN), eq(AssetEligibilityDefaults.MIN_D1_DAYS), eq(LAST_CLOSED)))
                .thenReturn(daily(120));

        assertEquals(OptionalInt.of(120), probe.closedDays(WebProviderCode.KRAKEN, "SOL"));
    }

    @Test
    @DisplayName("Moins de bougies que 120 : renvoie le nombre réel ; la bougie du jour n'est pas comptée")
    void partialHistory() {
        when(engine.getDailyDatasetOnSource(any(), any(), anyInt(), any())).thenReturn(daily(45));

        assertEquals(OptionalInt.of(45), probe.closedDays(WebProviderCode.OKX, "NEW"));
    }

    @Test
    @DisplayName("Provider sans client de bougies (Bitvavo, Gate EU, One Trading) : 0 jour, aucun appel")
    void providerWithoutCandleClient() {
        for (WebProviderCode code : new WebProviderCode[]{WebProviderCode.BITVAVO, WebProviderCode.GATE_EU, WebProviderCode.ONE_TRADING}) {
            assertEquals(OptionalInt.of(0), probe.closedDays(code, "SOL"));
        }
        verify(engine, never()).getDailyDatasetOnSource(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Paire inconnue du provider : 0 jour ; erreur transitoire : lecture impossible (vide)")
    void errors() {
        when(engine.getDailyDatasetOnSource(eq("GONE"), any(), anyInt(), any()))
                .thenThrow(new NoProviderAvailableException("GONE", new SymbolNotFoundException(MarketDataSource.KRAKEN, "GONEUSD", "inconnue")));
        when(engine.getDailyDatasetOnSource(eq("DOWN"), any(), anyInt(), any()))
                .thenThrow(new NoProviderAvailableException("DOWN", new ProviderUnavailableException(MarketDataSource.KRAKEN, "DOWNUSD", "429", null)));
        when(engine.getDailyDatasetOnSource(eq("NOMAP"), any(), anyInt(), any())).thenThrow(new NoProviderAvailableException("NOMAP", null));

        assertEquals(OptionalInt.of(0), probe.closedDays(WebProviderCode.KRAKEN, "GONE"));
        assertTrue(probe.closedDays(WebProviderCode.KRAKEN, "DOWN").isEmpty());
        assertEquals(OptionalInt.of(0), probe.closedDays(WebProviderCode.KRAKEN, "NOMAP"));
    }
}
