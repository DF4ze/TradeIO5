package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AssetNameRules : exclusions par nom, encodage earn propre à chaque provider")
class AssetNameRulesTest {

    private static Optional<IneligibilityReason> classify(String symbol, WebProviderCode provider) {
        return AssetNameRules.classify(symbol, provider);
    }

    @Test
    @DisplayName("Actifs ordinaires acceptés partout (dont LDO, JUP, PAXG)")
    void ordinaryAssets() {
        for (WebProviderCode provider : new WebProviderCode[]{WebProviderCode.KRAKEN, WebProviderCode.OKX, WebProviderCode.BINANCE,
                WebProviderCode.REVOLUT_X, WebProviderCode.GATE_EU, WebProviderCode.BITVAVO, WebProviderCode.ONE_TRADING}) {
            for (String symbol : new String[]{"BTC", "ETH", "PAXG", "SOL", "DOGE", "LDO", "JUP", "1INCH", "SUI"}) {
                assertTrue(classify(symbol, provider).isEmpty(), symbol + " chez " + provider);
            }
        }
    }

    @Test
    @DisplayName("Fiat et stablecoins")
    void fiatAndStables() {
        assertEquals(IneligibilityReason.FIAT, classify("EUR", WebProviderCode.KRAKEN).orElseThrow());
        assertEquals(IneligibilityReason.FIAT, classify("usd", WebProviderCode.OKX).orElseThrow());
        for (String stable : new String[]{"USDT", "USDC", "DAI", "FDUSD", "PYUSD", "EURC"}) {
            assertEquals(IneligibilityReason.STABLECOIN, classify(stable, WebProviderCode.OKX).orElseThrow(), stable);
        }
    }

    @Test
    @DisplayName("Tokens à levier / dérivés : 3L 3S 5L, UP DOWN, BULL BEAR")
    void leveraged() {
        for (String token : new String[]{"BTC3L", "ETH3S", "SOL5L", "BTCUP", "ETHDOWN", "BTCBULL", "ETHBEAR"}) {
            assertEquals(IneligibilityReason.LEVERAGED_TOKEN, classify(token, WebProviderCode.GATE_EU).orElseThrow(), token);
        }
    }

    @Test
    @DisplayName("Kraken : suffixes .S .F .M .B .P .T et ETH2 exclus, jamais l'actif nu")
    void krakenEarnSuffixes() {
        for (String earn : new String[]{"ETH.S", "DOT.S", "ETH.F", "BTC.M", "SOL.B", "ATOM.P", "XYZ.T", "ETH2"}) {
            assertEquals(IneligibilityReason.EARN_OR_WRAPPED, classify(earn, WebProviderCode.KRAKEN).orElseThrow(), earn);
        }
        assertTrue(classify("ETH", WebProviderCode.KRAKEN).isEmpty());
        assertTrue(classify("DOT", WebProviderCode.KRAKEN).isEmpty());
    }

    @Test
    @DisplayName("Binance : préfixe LD (Simple Earn) exclu ; le même nom n'est pas un earn chez Kraken / OKX")
    void binanceEarnPrefixIsPerProvider() {
        assertEquals(IneligibilityReason.EARN_OR_WRAPPED, classify("LDBTC", WebProviderCode.BINANCE).orElseThrow());
        assertEquals(IneligibilityReason.EARN_OR_WRAPPED, classify("LDUSDX", WebProviderCode.BINANCE).orElseThrow());
        assertTrue(classify("LDBTC", WebProviderCode.OKX).isEmpty());
        assertTrue(classify("LDBTC", WebProviderCode.KRAKEN).isEmpty());
        assertTrue(classify("ETH.S", WebProviderCode.OKX).isEmpty(), "le suffixe .S est un encodage Kraken");
    }

    @Test
    @DisplayName("Wrapped / liquid staking exclus chez tous les providers")
    void wrappedEverywhere() {
        for (String wrapped : new String[]{"WBTC", "WETH", "STETH", "WSTETH", "WBETH", "CBBTC", "JITOSOL"}) {
            assertEquals(IneligibilityReason.EARN_OR_WRAPPED, classify(wrapped, WebProviderCode.REVOLUT_X).orElseThrow(), wrapped);
            assertEquals(IneligibilityReason.EARN_OR_WRAPPED, classify(wrapped, WebProviderCode.KRAKEN).orElseThrow(), wrapped);
        }
    }

    @Test
    @DisplayName("Symbole vide ou nul refusé")
    void blank() {
        assertTrue(classify(null, WebProviderCode.OKX).isPresent());
        assertTrue(classify(" ", WebProviderCode.OKX).isPresent());
    }
}
