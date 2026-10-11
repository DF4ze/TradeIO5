package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.StablecoinMarketCapResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.stablecoin.StablecoinMarketCapProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator External - StablecoinMarketCapIndicator")
class StablecoinMarketCapIndicatorTest {

    private static final IndicatorParameters PARAMS = IndicatorParameters.builder()
            .indicatorType(IndicatorType.STABLECOIN_MARKET_CAP)
            .numerics(Map.of())
            .strings(Map.of())
            .booleans(Map.of())
            .credential(null)
            .build();

    @Test
    @DisplayName("compute() expose les 4 totaux quand le provider renvoie une réponse valide")
    void compute_exposesFourTotals_whenValid() {
        StablecoinMarketCapProvider provider = credential -> StablecoinMarketCapResponse.builder()
                .valid(true)
                .total(100.0)
                .totalPrevDay(90.0)
                .totalPrevWeek(80.0)
                .totalPrevMonth(70.0)
                .build();

        StablecoinMarketCapIndicator indicator = new StablecoinMarketCapIndicator(provider);

        IndicatorResult result = indicator.compute(null, PARAMS);

        assertTrue(result.isValid());
        assertEquals(100.0, result.getValue());
        assertEquals(100.0, result.getValues().get(StablecoinMarketCapIndicator.V_TOTAL));
        assertEquals(90.0, result.getValues().get(StablecoinMarketCapIndicator.V_TOTAL_PREV_DAY));
        assertEquals(80.0, result.getValues().get(StablecoinMarketCapIndicator.V_TOTAL_PREV_WEEK));
        assertEquals(70.0, result.getValues().get(StablecoinMarketCapIndicator.V_TOTAL_PREV_MONTH));
    }

    @Test
    @DisplayName("compute() retombe sur invalid() quand le provider échoue")
    void compute_returnsInvalid_whenProviderFails() {
        StablecoinMarketCapProvider provider = credential -> StablecoinMarketCapResponse.invalid();
        StablecoinMarketCapIndicator indicator = new StablecoinMarketCapIndicator(provider);

        IndicatorResult result = indicator.compute(null, PARAMS);

        assertFalse(result.isValid());
    }
}
