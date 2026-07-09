package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuote;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuoteProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator External - Sp500Indicator")
class Sp500IndicatorTest {

    @Test
    @DisplayName("compute() expose value=prix et values.lastTradeTime quand la quote porte un timestamp")
    void compute_exposesValueAndLastTradeTime() {
        FakeProvider provider = new FakeProvider(Map.of(
                Sp500Indicator.SYMBOL, new TwelveDataQuote(5600.25, 1751500800L, true)
        ));
        Sp500Indicator indicator = new Sp500Indicator(provider);

        IndicatorResult result = indicator.compute(null, parameters());

        assertTrue(result.isValid());
        assertEquals(5600.25, result.getValue(), 0.001);
        assertEquals(1751500800.0, result.getValues().get(Sp500Indicator.V_LAST_TRADE_TIME), 0.001);
    }

    @Test
    @DisplayName("compute() n'expose pas values quand le timestamp est absent (pas d'exception)")
    void compute_omitsValues_whenTimestampMissing() {
        FakeProvider provider = new FakeProvider(Map.of(
                Sp500Indicator.SYMBOL, new TwelveDataQuote(5600.25, null, null)
        ));
        Sp500Indicator indicator = new Sp500Indicator(provider);

        IndicatorResult result = indicator.compute(null, parameters());

        assertTrue(result.isValid());
        assertEquals(5600.25, result.getValue(), 0.001);
        assertNull(result.getValues());
    }

    @Test
    @DisplayName("compute() retourne invalid() quand la quote est absente")
    void compute_returnsInvalid_whenQuoteMissing() {
        FakeProvider provider = new FakeProvider(Map.of());
        Sp500Indicator indicator = new Sp500Indicator(provider);

        IndicatorResult result = indicator.compute(null, parameters());

        assertFalse(result.isValid());
    }

    private IndicatorParameters parameters() {
        return IndicatorParameters.builder()
                .credential(new ApiCredentialDTO(WebProviderCode.TWELVE_DATA, "k", "", "http://irrelevant"))
                .numerics(Map.of())
                .strings(Map.of())
                .booleans(Map.of())
                .build();
    }

    private static class FakeProvider implements TwelveDataQuoteProvider {
        private final Map<String, TwelveDataQuote> quotes;

        FakeProvider(Map<String, TwelveDataQuote> quotes) {
            this.quotes = quotes;
        }

        @Override
        public Map<String, Double> fetchPrices(ApiCredentialDTO credential, List<String> symbols) {
            throw new UnsupportedOperationException("not used by Sp500Indicator");
        }

        @Override
        public Map<String, TwelveDataQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols) {
            return quotes;
        }
    }
}
