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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DxyIndicator} passe par un seul appel {@code /quote} (pas {@code /price} +
 * {@code /quote}) depuis le 2026-07-15 (cf. javadoc classe) : le doublement du coût en crédits
 * Twelve Data introduit par le premier branchement de {@code MacroMarketOpinion} avait été
 * confirmé en conditions réelles (429 systématique, palier gratuit à 8 crédits/minute, DXY seul en
 * consommant 12 par évaluation) — corrigé en dérivant courant ET précédent d'une seule réponse
 * {@code /quote}.
 */
@DisplayName("Indicator External - DxyIndicator")
class DxyIndicatorTest {

    @Test
    @DisplayName("computeDxy() applique correctement la formule officielle (jeu de 6 valeurs connues, calcul indépendant en Python)")
    void computeDxy_appliesOfficialFormula() {
        // Rates illustratifs. La valeur attendue a été calculée indépendamment (Python :
        // 50.14348112 * eurusd**-0.576 * usdjpy**0.136 * gbpusd**-0.119 * usdcad**0.091 *
        // usdsek**0.042 * usdchf**0.036), pour vérifier que l'implémentation Java applique bien la
        // même formule, pas juste qu'elle est cohérente avec elle-même. Cross-validée contre une
        // vraie valeur DXY en conditions réelles le 2026-07-15 (100.92, même ordre de grandeur).
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
        FakeProvider provider = new FakeProvider(quotesWithoutPrevious(
                1.0850, 149.50, 1.2650, 1.3600, 10.4500, 0.8850
        ));
        DxyIndicator indicator = new DxyIndicator(provider);

        IndicatorResult result = indicator.compute(null, parameters());

        assertTrue(result.isValid());
        assertEquals(103.8614, result.getValue(), 0.01);
    }

    @Test
    @DisplayName("compute() retourne invalid() quand une des 6 paires manque, plutôt que d'approximer")
    void compute_returnsInvalid_whenOnePairMissing() {
        Map<String, TwelveDataQuote> incomplete = new HashMap<>(quotesWithoutPrevious(
                1.0850, 149.50, 1.2650, 1.3600, 10.4500, 0.8850
        ));
        incomplete.remove("USD/CHF");
        FakeProvider provider = new FakeProvider(incomplete);
        DxyIndicator indicator = new DxyIndicator(provider);

        IndicatorResult result = indicator.compute(null, parameters());

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("compute() expose values.previous quand les 6 quotes portent un previousClose (même appel /quote)")
    void compute_exposesPrevious_whenAllQuotesHavePreviousClose() {
        // previousClose délibérément différent de price, pour vérifier que le second calcul (pas
        // juste une recopie de la valeur courante) est bien utilisé.
        Map<String, TwelveDataQuote> quotes = new HashMap<>();
        quotes.put("EUR/USD", new TwelveDataQuote(1.0850, null, null, 1.0800));
        quotes.put("USD/JPY", new TwelveDataQuote(149.50, null, null, 149.00));
        quotes.put("GBP/USD", new TwelveDataQuote(1.2650, null, null, 1.2600));
        quotes.put("USD/CAD", new TwelveDataQuote(1.3600, null, null, 1.3550));
        quotes.put("USD/SEK", new TwelveDataQuote(10.4500, null, null, 10.4000));
        quotes.put("USD/CHF", new TwelveDataQuote(0.8850, null, null, 0.8800));

        FakeProvider provider = new FakeProvider(quotes);
        DxyIndicator indicator = new DxyIndicator(provider);

        IndicatorResult result = indicator.compute(null, parameters());

        assertTrue(result.isValid());
        assertEquals(103.8614, result.getValue(), 0.01, "value courante inchangée par l'ajout de previousClose");
        assertNotNull(result.getValues());
        Double expectedPrevious = DxyIndicator.computeDxy(Map.of(
                "EUR/USD", 1.0800, "USD/JPY", 149.00, "GBP/USD", 1.2600,
                "USD/CAD", 1.3550, "USD/SEK", 10.4000, "USD/CHF", 0.8800
        ));
        assertEquals(expectedPrevious, result.getValues().get(DxyIndicator.V_PREVIOUS), 0.0001);
    }

    @Test
    @DisplayName("compute() reste valid sans values.previous quand une seule paire n'a pas de previousClose (best-effort, jamais bloquant)")
    void compute_staysValid_whenOnePairMissingPreviousClose() {
        Map<String, TwelveDataQuote> quotes = new HashMap<>(quotesWithoutPrevious(
                1.0850, 149.50, 1.2650, 1.3600, 10.4500, 0.8850
        ));
        quotes.put("EUR/USD", new TwelveDataQuote(1.0850, null, null, 1.0800)); // seule paire avec previousClose
        FakeProvider provider = new FakeProvider(quotes);

        DxyIndicator indicator = new DxyIndicator(provider);
        IndicatorResult result = indicator.compute(null, parameters());

        assertTrue(result.isValid());
        assertEquals(103.8614, result.getValue(), 0.01);
        assertNull(result.getValues(), "aucune des 6 paires n'a de previousClose complet -> pas de values.previous");
    }

    private Map<String, TwelveDataQuote> quotesWithoutPrevious(
            double eurusd, double usdjpy, double gbpusd, double usdcad, double usdsek, double usdchf) {
        Map<String, TwelveDataQuote> quotes = new HashMap<>();
        quotes.put("EUR/USD", new TwelveDataQuote(eurusd, null, null, null));
        quotes.put("USD/JPY", new TwelveDataQuote(usdjpy, null, null, null));
        quotes.put("GBP/USD", new TwelveDataQuote(gbpusd, null, null, null));
        quotes.put("USD/CAD", new TwelveDataQuote(usdcad, null, null, null));
        quotes.put("USD/SEK", new TwelveDataQuote(usdsek, null, null, null));
        quotes.put("USD/CHF", new TwelveDataQuote(usdchf, null, null, null));
        return quotes;
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
            throw new UnsupportedOperationException("DxyIndicator n'utilise plus /price depuis le 2026-07-15");
        }

        @Override
        public Map<String, TwelveDataQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols) {
            return quotes;
        }
    }
}
