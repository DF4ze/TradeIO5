package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CandlePairResolver : paire D1 USDC > USDT > USD du catalogue du provider, convention sinon")
class CandlePairResolverTest {

    private final InstrumentCatalog catalog = mock(InstrumentCatalog.class);
    private final CandlePairResolver resolver = new CandlePairResolver(catalog);

    private static ExchangeInstrument inst(String base, String quote) {
        return ExchangeInstrument.builder().provider(WebProviderCode.OKX).instId(base + "-" + quote).base(base).quote(quote)
                .state(InstrumentCatalog.LIVE_STATE).minSz(BigDecimal.ONE).lotSz(BigDecimal.ONE).tickSz(BigDecimal.ONE).build();
    }

    private void catalogOf(ExchangeInstrument... instruments) {
        when(catalog.supports(WebProviderCode.OKX)).thenReturn(true);
        when(catalog.storedInstruments(WebProviderCode.OKX)).thenReturn(List.of(instruments));
    }

    @Test
    @DisplayName("USDC présent => USDC, même si USDT existe")
    void usdcFirst() {
        catalogOf(inst("SOL", "USDT"), inst("SOL", "USDC"), inst("SOL", "USD"));
        assertEquals(ProviderSymbolMapper.forSource("SOL", MarketDataSource.OKX, "USDC"), resolver.pairFor("SOL", MarketDataSource.OKX));
    }

    @Test
    @DisplayName("Pas d'USDC => USDT")
    void usdtSecond() {
        catalogOf(inst("SOL", "USD"), inst("SOL", "USDT"));
        assertEquals(ProviderSymbolMapper.forSource("SOL", MarketDataSource.OKX, "USDT"), resolver.pairFor("SOL", MarketDataSource.OKX));
    }

    @Test
    @DisplayName("Ni USDC ni USDT => USD")
    void usdLast() {
        catalogOf(inst("SOL", "USD"));
        assertEquals(ProviderSymbolMapper.forSource("SOL", MarketDataSource.OKX, "USD"), resolver.pairFor("SOL", MarketDataSource.OKX));
    }

    @Test
    @DisplayName("Actif absent du catalogue => convention historique")
    void unknownAssetKeepsConvention() {
        catalogOf(inst("ETH", "USDC"));
        assertEquals(ProviderSymbolMapper.forSource("SOL", MarketDataSource.OKX), resolver.pairFor("SOL", MarketDataSource.OKX));
    }

    @Test
    @DisplayName("Binance (source de bougies sans catalogue) => convention <ACTIF>USDT")
    void binanceKeepsConvention() {
        assertEquals(ProviderSymbolMapper.forSource("SOL", MarketDataSource.BINANCE), resolver.pairFor("SOL", MarketDataSource.BINANCE));
    }
}
