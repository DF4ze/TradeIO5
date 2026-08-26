package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCache;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuote;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuoteProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test de bout en bout avec les VRAIES classes de production ({@link DxyIndicator} +
 * {@link IndicatorEngine} + {@link IndicatorRegistry} + {@link IndicatorCache} — pas de fake
 * {@code Indicator} de complaisance comme dans {@code IndicatorExecutionKeyTest}), pour prouver
 * que le scénario exact de l'incident 2026-08-17 (DXY évalué pour BTC puis ETH dans la même
 * fenêtre, via {@code get_indicator}/{@code get_opinion(MACRO)}) ne déclenche plus qu'un seul
 * appel Twelve Data {@code /quote} au lieu de deux (2×6=12 crédits/minute, au-dessus de la limite
 * gratuite de 8 — cf. javadoc {@code DxyIndicator}/{@code Indicator#isGlobal()}).
 * <p>
 * Complète {@code IndicatorExecutionKeyTest} (mécanisme générique, {@code Indicator} fake) en
 * validant le chemin réel : {@code DxyIndicator.isGlobal()=true} + le vrai calcul de clé de
 * {@code IndicatorExecutionKey.of(...)}.
 */
@DisplayName("DxyIndicator - partage de cache réel via IndicatorEngine")
class DxyIndicatorCacheSharingTest {

    @Test
    @DisplayName("2 évaluations DXY (BTC puis ETH) via le vrai IndicatorEngine ne déclenchent qu'un seul appel /quote Twelve Data")
    void dxyEvaluatedForTwoSymbols_hitsTwelveDataOnlyOnce() {
        AtomicInteger fetchQuotesCalls = new AtomicInteger();
        DxyIndicator dxyIndicator = new DxyIndicator(new CountingProvider(fetchQuotesCalls));

        IndicatorEngine engine = new IndicatorEngine(
                new IndicatorRegistry(List.of(dxyIndicator)),
                new IndicatorCache()
        );

        engine.execute(contextFor("BTC"), dxyParameters());
        engine.execute(contextFor("ETH"), dxyParameters());

        assertEquals(1, fetchQuotesCalls.get(),
                "incident 2026-08-17 : BTC puis ETH consommait 2x6=12 crédits Twelve Data/minute "
                        + "(limite gratuite : 8) ; un seul appel réseau doit désormais suffire pour les deux symboles");
    }

    @Test
    @DisplayName("2 évaluations DXY pour le MÊME symbole ne déclenchent qu'un seul appel (non-régression du cache de base)")
    void dxyEvaluatedTwiceForSameSymbol_hitsTwelveDataOnlyOnce() {
        AtomicInteger fetchQuotesCalls = new AtomicInteger();
        DxyIndicator dxyIndicator = new DxyIndicator(new CountingProvider(fetchQuotesCalls));

        IndicatorEngine engine = new IndicatorEngine(
                new IndicatorRegistry(List.of(dxyIndicator)),
                new IndicatorCache()
        );

        engine.execute(contextFor("BTC"), dxyParameters());
        engine.execute(contextFor("BTC"), dxyParameters());

        assertEquals(1, fetchQuotesCalls.get(),
                "le cache de base (même symbole, même timeframe) doit continuer à fonctionner comme avant");
    }

    private IndicatorContext contextFor(String symbol) {
        FixedDomainClock clock = new FixedDomainClock(Instant.parse("2025-01-01T12:00:00Z"));
        return new IndicatorContext(
                symbol,
                TimeFrame.H1,
                MarketDataset.builder().pair(symbol).timeFrame(TimeFrame.H1).build(),
                Map.of(),
                clock
        );
    }

    private IndicatorParameters dxyParameters() {
        return IndicatorParameters.builder()
                .indicatorType(IndicatorType.DXY)
                .credential(new ApiCredentialDTO(WebProviderCode.TWELVE_DATA, "k", "", "http://irrelevant"))
                .numerics(Map.of())
                .strings(Map.of())
                .booleans(Map.of())
                .build();
    }

    /** Même patron que {@code DxyIndicatorTest.FakeProvider}, avec un compteur d'appels réseau. */
    private static class CountingProvider implements TwelveDataQuoteProvider {
        private final AtomicInteger fetchQuotesCalls;

        CountingProvider(AtomicInteger fetchQuotesCalls) {
            this.fetchQuotesCalls = fetchQuotesCalls;
        }

        @Override
        public Map<String, Double> fetchPrices(ApiCredentialDTO credential, List<String> symbols) {
            throw new UnsupportedOperationException("DxyIndicator n'utilise plus /price depuis le 2026-07-15");
        }

        @Override
        public Map<String, TwelveDataQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols) {
            fetchQuotesCalls.incrementAndGet();
            Map<String, TwelveDataQuote> quotes = new HashMap<>();
            quotes.put("EUR/USD", new TwelveDataQuote(1.0850, null, null, 1.0800));
            quotes.put("USD/JPY", new TwelveDataQuote(149.50, null, null, 149.00));
            quotes.put("GBP/USD", new TwelveDataQuote(1.2650, null, null, 1.2600));
            quotes.put("USD/CAD", new TwelveDataQuote(1.3600, null, null, 1.3550));
            quotes.put("USD/SEK", new TwelveDataQuote(10.4500, null, null, 10.4000));
            quotes.put("USD/CHF", new TwelveDataQuote(0.8850, null, null, 0.8800));
            return quotes;
        }
    }
}
