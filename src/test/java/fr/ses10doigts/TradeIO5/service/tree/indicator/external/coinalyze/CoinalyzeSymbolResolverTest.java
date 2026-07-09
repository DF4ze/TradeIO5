package fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("CoinalyzeSymbolResolver.findCoinalyzeSymbol")
class CoinalyzeSymbolResolverTest {

    @Test
    @DisplayName("résout le symbole Coinalyze via exchange (nom) + symbol_on_exchange, sans deviner le suffixe")
    void findCoinalyzeSymbol_resolvesViaExchangeAndSymbolOnExchange() {
        CoinalyzeExchange binance = new CoinalyzeExchange();
        binance.setCode("A");
        binance.setName("Binance");

        CoinalyzeExchange bybit = new CoinalyzeExchange();
        bybit.setCode("6");
        bybit.setName("Bybit");

        CoinalyzeFutureMarket binanceBtc = new CoinalyzeFutureMarket();
        binanceBtc.setSymbol("BTCUSDT_PERP.A");
        binanceBtc.setExchange("A");
        binanceBtc.setSymbolOnExchange("BTCUSDT");

        CoinalyzeFutureMarket bybitBtc = new CoinalyzeFutureMarket();
        bybitBtc.setSymbol("BTCUSDT_PERP.6");
        bybitBtc.setExchange("6");
        bybitBtc.setSymbolOnExchange("BTCUSDT");

        String resolved = CoinalyzeSymbolResolver.findCoinalyzeSymbol(
                List.of(binance, bybit), List.of(binanceBtc, bybitBtc), "Binance", "BTCUSDT");

        assertEquals("BTCUSDT_PERP.A", resolved);
    }

    @Test
    @DisplayName("retourne null si l'exchange demandé est introuvable")
    void findCoinalyzeSymbol_returnsNull_whenExchangeNotFound() {
        CoinalyzeExchange bybit = new CoinalyzeExchange();
        bybit.setCode("6");
        bybit.setName("Bybit");

        CoinalyzeFutureMarket bybitBtc = new CoinalyzeFutureMarket();
        bybitBtc.setSymbol("BTCUSDT_PERP.6");
        bybitBtc.setExchange("6");
        bybitBtc.setSymbolOnExchange("BTCUSDT");

        String resolved = CoinalyzeSymbolResolver.findCoinalyzeSymbol(
                List.of(bybit), List.of(bybitBtc), "Binance", "BTCUSDT");

        assertNull(resolved);
    }

    @Test
    @DisplayName("retourne null si le symbole n'est tradé sur aucun marché de l'exchange demandé")
    void findCoinalyzeSymbol_returnsNull_whenSymbolNotFound() {
        CoinalyzeExchange binance = new CoinalyzeExchange();
        binance.setCode("A");
        binance.setName("Binance");

        CoinalyzeFutureMarket binanceEth = new CoinalyzeFutureMarket();
        binanceEth.setSymbol("ETHUSDT_PERP.A");
        binanceEth.setExchange("A");
        binanceEth.setSymbolOnExchange("ETHUSDT");

        String resolved = CoinalyzeSymbolResolver.findCoinalyzeSymbol(
                List.of(binance), List.of(binanceEth), "Binance", "BTCUSDT");

        assertNull(resolved);
    }
}
