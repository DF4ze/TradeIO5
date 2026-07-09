package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuote;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuoteProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator External - DxyIndicator")
class DxyIndicatorTest {

    @Test
    @DisplayName("computeDxy() applique correctement la formule officielle (jeu de 6 valeurs connues, calcul indépendant en Python)")
    void computeDxy_appliesOfficialFormula() {
        // Rates illustratifs (pas une capture réelle simultanée des 6 paires — aucune clé Twelve
        // Data disponible au moment de cette implémentation, cf. avertissement de la classe). La
        // valeur attendue a été calculée indépendamment (Python : 50.14348112 * eurusd**-0.576 *
        // usdjpy**0.136 * gbpusd**-0.119 * usdcad**0.091 * usdsek**0.042 * usdchf**0.036), pour
        // vérifier que l'implémentation Java applique bien la même formule, pas juste qu'elle est
        // cohérente avec elle-même. Cross-validation contre une vraie valeur DXY de référence à
        // faire dès qu'une clé Twelve Data est disponible (cf. Definition of done, item E/F).
        Map<String, Double> prices = Map.of(
                "EUR/USD", 1.0850,
                "USD/JPY", 149.50,
                "GBP/USD", 1.2650,
                "USD/CAD", 1.3600,
                "USD/SEK", 10.4500,
                "USD/CHF", 0.8850
        );

        Double dxy = DxyIndicator.computeDxy(prices);

        assertEquals(103.8614, dxy, 0.01);
    }

    @Test
    @DisplayName("computeDxy() retourne null si une des 6 paires manque")
    void computeDxy_returnsNull_whenOnePairMissing() {
        Map<String, Double> prices = new HashMap<>(Map.of(
                "EUR/USD", 1.0850,
                "USD/JPY", 149.50,
                "GBP/USD", 1.2650,
                "USD/CAD", 1.3600,
                "USD/SEK", 10.4500
                // USD/CHF manquant
        ));

        assertNull(DxyIndicator.computeDxy(prices));
    }

    @Test
    @DisplayName("computeDxy() retourne null si la map est vide/nulle")
    void computeDxy_returnsNull_whenPricesEmptyOrNull() {
        assertNull(DxyIndicator.computeDxy(Map.of()));
        assertNull(DxyIndicator.computeDxy(null));
    }

    @Test
    @DisplayName("compute() retourne un IndicatorResult valid avec la valeur DXY quand les 6 paires sont disponibles")
    void compute_returnsValidResult_whenAllPairsAvailable() {
        FakeProvider provider = new FakeProvider(Map.of(
                "EUR/USD", 1.0850,
                "USD/JPY", 149.50,
                "GBP/USD", 1.2650,
                "USD/CAD", 1.3600,
                "USD/SEK", 10.4500,
                "USD/CHF", 0.8850
        ));
        DxyIndicator indicator = new DxyIndicator(provider);

        IndicatorResult result = indicator.compute(null, parameters());

        assertTrue(result.isValid());
        assertEquals(103.8614, result.getValue(), 0.01);
    }

    @Test
    @DisplayName("compute() retourne invalid() quand une des 6 paires manque, plutôt que d'approximer")
    void compute_returnsInvalid_whenOnePairMissing() {
        Map<String, Double> incomplete = new HashMap<>(Map.of(
                "EUR/USD", 1.0850,
                "USD/JPY", 149.50,
                "GBP/USD", 1.2650,
                "USD/CAD", 1.3600,
                "USD/SEK", 10.4500
        ));
        FakeProvider provider = new FakeProvider(incomplete);
        DxyIndicator indicator = new DxyIndicator(provider);

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
        private final Map<String, Double> prices;

        FakeProvider(Map<String, Double> prices) {
            this.prices = prices;
        }

        @Override
        public Map<String, Double> fetchPrices(ApiCredentialDTO credential, List<String> symbols) {
            return prices;
        }

        @Override
        public Map<String, TwelveDataQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols) {
            throw new UnsupportedOperationException("not used by DxyIndicator");
        }
    }
}
