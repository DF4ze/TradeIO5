package fr.ses10doigts.tradeIO5.service.market.dataset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProviderSymbolMapperTest {

    @Test
    void binancePair_suffixesBareSymbol() {
        assertEquals("BTCUSDT", ProviderSymbolMapper.binancePair("BTC"));
        assertEquals("PAXGUSDT", ProviderSymbolMapper.binancePair("PAXG"));
    }

    @Test
    void binancePair_keepsAlreadySuffixedPair() {
        assertEquals("ETHUSDT", ProviderSymbolMapper.binancePair("ETHUSDT"));
    }

    @Test
    void binancePair_nullStaysNull() {
        assertNull(ProviderSymbolMapper.binancePair(null));
    }
}
