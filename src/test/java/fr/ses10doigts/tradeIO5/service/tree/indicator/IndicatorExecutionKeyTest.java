package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorExecutionKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Régression incident 2026-08-17 (signalé par Clem, log applicatif réel) : {@code DxyIndicator}
 * consommait 12 crédits Twelve Data/minute (limite gratuite : 8) parce que
 * {@link IndicatorExecutionKey} incluait le {@code symbol} appelant (et le {@code MarketDataset},
 * qui l'embarque aussi via son champ {@code pair}) dans la clé de cache, alors que DXY — comme
 * SP500, NASDAQ, FEAR_GREED, STABLECOIN_MARKET_CAP, ETF_FLOW, cf. {@link Indicator#isGlobal()} —
 * ne dépend jamais du symbole interrogé : deux évaluations sur des symboles différents (ex: BTC
 * puis ETH dans la même minute) déclenchaient chacune un appel réseau distinct pour une seule et
 * même donnée globale, doublant (ou plus) le coût réel.
 * <p>
 * Suite volontairement sans Spring ({@code IndicatorRegistry}/{@code IndicatorCache} s'instancient
 * directement), même patron que {@code IndicatorCacheTest}.
 */
@DisplayName("IndicatorExecutionKey / IndicatorEngine - partage de cache des indicateurs globaux")
class IndicatorExecutionKeyTest {

    private static final FixedDomainClock CLOCK = new FixedDomainClock(Instant.parse("2025-01-01T12:00:00Z"));

    private IndicatorContext contextFor(String symbol) {
        return new IndicatorContext(
                symbol,
                TimeFrame.H1,
                MarketDataset.builder().pair(symbol).timeFrame(TimeFrame.H1).build(),
                Map.of(),
                CLOCK
        );
    }

    private IndicatorParameters params(IndicatorType type) {
        return new IndicatorParameters(type, Map.of(), Map.of(), Map.of(), null);
    }

    @Test
    @DisplayName("un indicateur global (isGlobal=true) produit la même clé de cache pour deux symboles différents")
    void of_sharesCacheKeyAcrossSymbols_whenIndicatorIsGlobal() {
        Indicator globalIndicator = fakeIndicator(IndicatorType.DXY, true);
        IndicatorParameters parameters = params(IndicatorType.DXY);

        IndicatorExecutionKey btcKey = IndicatorExecutionKey.of(globalIndicator, contextFor("BTC"), parameters);
        IndicatorExecutionKey ethKey = IndicatorExecutionKey.of(globalIndicator, contextFor("ETH"), parameters);

        assertEquals(btcKey, ethKey,
                "un indicateur global doit partager la même entrée de cache quel que soit le symbole appelant");
    }

    @Test
    @DisplayName("un indicateur non-global (comportement par défaut) produit des clés distinctes par symbole")
    void of_keepsDistinctCacheKeys_whenIndicatorIsNotGlobal() {
        Indicator localIndicator = fakeIndicator(IndicatorType.RSI, false);
        IndicatorParameters parameters = params(IndicatorType.RSI);

        IndicatorExecutionKey btcKey = IndicatorExecutionKey.of(localIndicator, contextFor("BTC"), parameters);
        IndicatorExecutionKey ethKey = IndicatorExecutionKey.of(localIndicator, contextFor("ETH"), parameters);

        assertNotEquals(btcKey, ethKey,
                "un indicateur non-global doit rester isolé par symbole (comportement historique, non régressé)");
    }

    @Test
    @DisplayName("IndicatorEngine ne calcule/n'appelle un indicateur global qu'une seule fois pour deux symboles différents")
    void engine_executesGlobalIndicatorOnce_acrossDifferentSymbols() {
        AtomicInteger computeCalls = new AtomicInteger();
        Indicator globalIndicator = countingIndicator(IndicatorType.DXY, true, computeCalls);

        IndicatorEngine engine = new IndicatorEngine(
                new IndicatorRegistry(List.of(globalIndicator)),
                new IndicatorCache()
        );
        IndicatorParameters parameters = params(IndicatorType.DXY);

        engine.execute(contextFor("BTC"), parameters);
        engine.execute(contextFor("ETH"), parameters);

        assertEquals(1, computeCalls.get(),
                "un indicateur global ne doit être calculé qu'une fois, pas une fois par symbole appelant "
                        + "(incident 2026-08-17 : DxyIndicator/TwelveDataQuoteClient appelé 2x = 12 crédits/minute "
                        + "au lieu de 6, dépassant la limite gratuite de 8)");
    }

    @Test
    @DisplayName("IndicatorEngine recalcule un indicateur non-global pour chaque symbole (comportement historique préservé)")
    void engine_executesNonGlobalIndicatorPerSymbol() {
        AtomicInteger computeCalls = new AtomicInteger();
        Indicator localIndicator = countingIndicator(IndicatorType.RSI, false, computeCalls);

        IndicatorEngine engine = new IndicatorEngine(
                new IndicatorRegistry(List.of(localIndicator)),
                new IndicatorCache()
        );
        IndicatorParameters parameters = params(IndicatorType.RSI);

        engine.execute(contextFor("BTC"), parameters);
        engine.execute(contextFor("ETH"), parameters);

        assertEquals(2, computeCalls.get(),
                "un indicateur non-global doit rester recalculé par symbole (pas de régression sur RSI/EMA/... "
                        + "ni sur OPEN_INTEREST/FUNDING_RATE/LIQUIDATIONS qui varient réellement par actif)");
    }

    private Indicator fakeIndicator(IndicatorType type, boolean global) {
        return countingIndicator(type, global, new AtomicInteger());
    }

    private Indicator countingIndicator(IndicatorType type, boolean global, AtomicInteger computeCalls) {
        return new Indicator() {
            @Override
            public IndicatorType getType() {
                return type;
            }

            @Override
            public boolean isGlobal() {
                return global;
            }

            @Override
            public int getRequiredData(IndicatorParameters parameters) {
                return 0;
            }

            @Override
            public IndicatorResult compute(IndicatorContext context, IndicatorParameters parameters) {
                computeCalls.incrementAndGet();
                return IndicatorResult.builder().valid(true).value(1.0).build();
            }

            @Override
            public List<String> getParametersNames() {
                return List.of();
            }
        };
    }
}
