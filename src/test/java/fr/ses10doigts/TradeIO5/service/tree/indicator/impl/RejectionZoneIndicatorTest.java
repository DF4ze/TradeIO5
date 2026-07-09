package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorDependencyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste {@link RejectionZoneIndicator} en isolation (sans {@code IndicatorEngine}), en injectant
 * directement un snapshot ATR dans {@code IndicatorContext.dependencies()} plutôt qu'en laissant
 * l'ATR se calculer sur les bougies synthétiques — patron déjà utilisé pour tester les autres
 * indicateurs purs de {@code impl/} sur des jeux de bougies construits à la main (cf.
 * {@code AdxIndicatorTest}). Chaque bougie synthétique est construite pour matcher ou volontairement
 * violer une des 3 conditions de la définition (mèche/corps, mèche/ATR, position de clôture), pas
 * pour ressembler à un vrai marché.
 * <p>
 * Rappel important porté par le prompt d'implémentation (Lot 3, item J) : ces tests valident que
 * le <b>code</b> respecte la formule définie ; ils ne valident pas que la formule elle-même a une
 * quelconque valeur prédictive — c'est l'objet du protocole séparé documenté dans
 * {@code docs/calibration-rejection-zone.md}.
 */
@DisplayName("Indicator - RejectionZone")
class RejectionZoneIndicatorTest {

    private final RejectionZoneIndicator indicator = new RejectionZoneIndicator();

    private static DomainClock clock;

    @BeforeAll
    static void init() {
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    // O, H, L, C, volume "sans histoire" : range non nul mais mèches proportionnées au corps,
    // ne déclenche jamais un rejet quel que soit l'ATR utilisé dans ces tests.
    private static double[] boring() {
        return new double[]{100, 100.6, 99.9, 100.5, 10};
    }

    private static IndicatorContext contextWithAtr(List<double[]> ohlcv, Double atrValue, DomainClock clock) {
        List<MarketData> data = ohlcv.stream()
                .map(c -> MarketData.builder()
                        .open(BigDecimal.valueOf(c[0]))
                        .high(BigDecimal.valueOf(c[1]))
                        .low(BigDecimal.valueOf(c[2]))
                        .close(BigDecimal.valueOf(c[3]))
                        .volume(BigDecimal.valueOf(c.length > 4 ? c[4] : 0))
                        .build())
                .toList();

        MarketDataset series = MarketDataset.builder()
                .marketDatas(data)
                .timeFrame(TimeFrame.MIN1)
                .build();

        Map<IndicatorDependencyKey, IndicatorSnapshot> dependencies;
        if (atrValue == null) {
            dependencies = Map.of();
        } else {
            IndicatorDependencyKey atrKey =
                    new IndicatorDependencyKey(IndicatorType.ATR, RejectionZoneIndicator.ATR_DEPENDENCY_ROLE);
            IndicatorSnapshot atrSnapshot = IndicatorSnapshot.builder()
                    .indicatorType(IndicatorType.ATR)
                    .result(IndicatorResult.builder().valid(true).value(atrValue).build())
                    .build();
            dependencies = Map.of(atrKey, atrSnapshot);
        }

        return new IndicatorContext("BTCUSDT", series.getTimeFrame(), series, dependencies, clock);
    }

    private static IndicatorParameters params(double k1, double k2, double p, int lookback,
                                               double clusterDistance, int atrPeriod) {
        return IndicatorParameters.builder()
                .indicatorType(IndicatorType.REJECTION_ZONE)
                .numerics(Map.of(
                        RejectionZoneIndicator.P_K1, k1,
                        RejectionZoneIndicator.P_K2, k2,
                        RejectionZoneIndicator.P_P, p,
                        RejectionZoneIndicator.P_LOOKBACK, (double) lookback,
                        RejectionZoneIndicator.P_CLUSTER_DISTANCE, clusterDistance,
                        RejectionZoneIndicator.P_ATR_PERIOD, (double) atrPeriod
                ))
                .strings(Map.of())
                .booleans(Map.of())
                .build();
    }

    @Test
    @DisplayName("Détecte un rejet haussier isolé (mèche basse >> corps et ATR, clôture repoussée en haut)")
    void bullishRejection_isDetected() {
        List<double[]> ohlcv = List.of(
                boring(), boring(), boring(), boring(),
                new double[]{100, 104.2, 90, 104, 10} // O,H,L,C : lowerWick=10, body=4, range=14.2
        );
        IndicatorContext context = contextWithAtr(ohlcv, 5.0, clock);
        IndicatorParameters p = params(1.5, 0.5, 0.66, 5, 0.5, 3);

        IndicatorResult result = indicator.compute(context, p);

        assertTrue(result.isValid());
        assertEquals(90.0, result.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_PRICE), 0.001);
        assertEquals(1.0, result.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_TOUCHES), 0.001);
        assertNull(result.getValues().get(RejectionZoneIndicator.V_NEAREST_RESISTANCE_PRICE));
    }

    @Test
    @DisplayName("Détecte un rejet baissier isolé (mèche haute >> corps et ATR, clôture repoussée en bas)")
    void bearishRejection_isDetected() {
        List<double[]> ohlcv = List.of(
                boring(), boring(), boring(), boring(),
                new double[]{100, 110, 95.8, 96, 10} // O,H,L,C : upperWick=10, body=4, range=14.2
        );
        IndicatorContext context = contextWithAtr(ohlcv, 5.0, clock);
        IndicatorParameters p = params(1.5, 0.5, 0.66, 5, 0.5, 3);

        IndicatorResult result = indicator.compute(context, p);

        assertTrue(result.isValid());
        assertEquals(110.0, result.getValues().get(RejectionZoneIndicator.V_NEAREST_RESISTANCE_PRICE), 0.001);
        assertEquals(1.0, result.getValues().get(RejectionZoneIndicator.V_NEAREST_RESISTANCE_TOUCHES), 0.001);
        assertNull(result.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_PRICE));
    }

    @Test
    @DisplayName("Ne détecte rien sur une quasi-doji (mèches symétriques, clôture non repoussée vers un extrême)")
    void quasiDoji_isNotDetected() {
        List<double[]> ohlcv = List.of(
                boring(), boring(), boring(), boring(),
                // corps quasi nul, mais clôture au centre du range (ni haut ni bas) :
                // la condition de position de clôture doit rejeter cette bougie.
                new double[]{100.05, 105, 95, 99.95, 10}
        );
        IndicatorContext context = contextWithAtr(ohlcv, 2.0, clock);
        IndicatorParameters p = params(1.5, 0.5, 0.66, 5, 0.5, 3);

        IndicatorResult result = indicator.compute(context, p);

        assertTrue(result.isValid());
        assertNull(result.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_PRICE));
        assertNull(result.getValues().get(RejectionZoneIndicator.V_NEAREST_RESISTANCE_PRICE));
    }

    @Test
    @DisplayName("Ne détecte rien quand la mèche est insignifiante par rapport à l'ATR (marché très volatil)")
    void wickInsignificantVersusAtr_isNotDetected() {
        List<double[]> ohlcv = List.of(
                boring(), boring(), boring(), boring(),
                // mèche basse (3) nettement > corps (1) mais ATR très élevé (10) : k2*ATR=5 > mèche.
                new double[]{100, 101.1, 97, 101, 10}
        );
        IndicatorContext context = contextWithAtr(ohlcv, 10.0, clock);
        IndicatorParameters p = params(1.5, 0.5, 0.66, 5, 0.5, 3);

        IndicatorResult result = indicator.compute(context, p);

        assertTrue(result.isValid());
        assertNull(result.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_PRICE));
    }

    @Test
    @DisplayName("Regroupe 2 rejets proches en une zone avec un score de force supérieur à un rejet isolé")
    void twoNearbyRejections_clusterIntoStrongerZoneThanIsolatedOne() {
        double[] rejectionAt90 = {100, 104.2, 90, 104, 10};
        double[] rejectionAt91 = {100, 104.2, 91, 104, 10}; // à 1 de distance : < clusterDistance (0.5*atr=2.5)

        List<double[]> clustered = List.of(
                rejectionAt90, boring(), rejectionAt91, boring(), boring(), boring()
        );
        List<double[]> isolated = List.of(
                rejectionAt90, boring(), boring(), boring(), boring(), boring()
        );

        IndicatorParameters p = params(1.5, 0.5, 0.66, 6, 0.5, 3);

        IndicatorResult clusteredResult = indicator.compute(contextWithAtr(clustered, 5.0, clock), p);
        IndicatorResult isolatedResult = indicator.compute(contextWithAtr(isolated, 5.0, clock), p);

        assertTrue(clusteredResult.isValid());
        assertTrue(isolatedResult.isValid());

        assertEquals(2.0, clusteredResult.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_TOUCHES), 0.001);
        assertEquals(1.0, isolatedResult.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_TOUCHES), 0.001);

        double clusteredStrength = clusteredResult.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_STRENGTH);
        double isolatedStrength = isolatedResult.getValues().get(RejectionZoneIndicator.V_NEAREST_SUPPORT_STRENGTH);
        assertTrue(clusteredStrength > isolatedStrength,
                "Une zone à 2 touches doit avoir un score de force supérieur à une zone à 1 touche, got "
                        + clusteredStrength + " vs " + isolatedStrength);
    }

    @Test
    @DisplayName("Historique trop court pour le lookback demandé : résultat invalide")
    void insufficientLookbackData_returnsInvalid() {
        List<double[]> ohlcv = List.of(boring(), boring(), boring()); // 3 < lookback (10)
        IndicatorContext context = contextWithAtr(ohlcv, 5.0, clock);
        IndicatorParameters p = params(1.5, 0.5, 0.66, 10, 0.5, 3);

        IndicatorResult result = indicator.compute(context, p);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("getRequiredData() reflète lookback + période ATR (warmup du lissage de Wilder)")
    void getRequiredData_reflectsLookbackPlusAtrPeriod() {
        IndicatorParameters p = params(1.5, 0.5, 0.66, 200, 0.5, 14);
        assertEquals(214, indicator.getRequiredData(p));
    }

    @Test
    @DisplayName("Dépendance ATR manquante ou invalide : résultat invalide, pas d'exception")
    void missingAtrDependency_returnsInvalid() {
        List<double[]> ohlcv = List.of(boring(), boring(), boring(), boring(), boring());
        IndicatorContext context = contextWithAtr(ohlcv, null, clock);
        IndicatorParameters p = params(1.5, 0.5, 0.66, 5, 0.5, 3);

        IndicatorResult result = indicator.compute(context, p);

        assertFalse(result.isValid());
    }
}
