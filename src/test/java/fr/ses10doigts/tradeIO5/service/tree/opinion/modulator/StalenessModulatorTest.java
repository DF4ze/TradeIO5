package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie l'adaptateur {@link StalenessModulator} (étude "unification-confidence-modulator",
 * 2026-07-16, §2/§5 point 2) : une seule instance reçoit plusieurs {@link StalenessModulator.StalenessInput}
 * et retient le facteur le plus conservateur (minimum), reproduisant le
 * {@code Math.min(sp500Staleness, nasdaqStaleness)} historique de {@code MacroMarketOpinion}. Aucun
 * test dédié n'existait pour cette classe avant ce lot.
 */
@DisplayName("StalenessModulator - facteur le plus conservateur parmi plusieurs quotes")
class StalenessModulatorTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final double STALE_THRESHOLD_HOURS = 18.0;

    @Test
    @DisplayName("toutes les quotes fraîches => applied=true, facteur neutre 1.0")
    void allFresh_neutralFactor() {
        long oneHourAgo = NOW.minusSeconds(3600).getEpochSecond();
        StalenessModulator modulator = new StalenessModulator(NOW, STALE_THRESHOLD_HOURS,
                new StalenessModulator.StalenessInput("SP500", oneHourAgo),
                new StalenessModulator.StalenessInput("NASDAQ", oneHourAgo));

        ModulationResult result = modulator.evaluate(null, null);

        assertTrue(result.applied());
        assertEquals(1.0, result.factor(), 1e-9);
    }

    @Test
    @DisplayName("une quote figée parmi plusieurs => retient le facteur le plus conservateur (minimum)")
    void oneStaleAmongMany_retainsMostConservativeFactor() {
        long fresh = NOW.minusSeconds(3600).getEpochSecond(); // 1h
        long fortyHoursAgo = NOW.minusSeconds(60L * 60 * 40).getEpochSecond(); // 40h, figée
        double expectedStaleFactor = STALE_THRESHOLD_HOURS / 40.0;

        StalenessModulator modulator = new StalenessModulator(NOW, STALE_THRESHOLD_HOURS,
                new StalenessModulator.StalenessInput("SP500", fresh),
                new StalenessModulator.StalenessInput("NASDAQ", fortyHoursAgo));

        ModulationResult result = modulator.evaluate(null, null);

        assertTrue(result.applied());
        assertEquals(expectedStaleFactor, result.factor(), 1e-6);
        assertTrue(result.reason().contains("NASDAQ"),
                "la raison doit identifier la quote la plus limitante : " + result.reason());
    }

    @Test
    @DisplayName("timestamp absent pour une entrée => cette entrée seule reste neutre (1.0), pas de contagion")
    void missingTimestampForOneInput_thatInputStaysNeutral() {
        StalenessModulator modulator = new StalenessModulator(NOW, STALE_THRESHOLD_HOURS,
                new StalenessModulator.StalenessInput("SP500", null),
                new StalenessModulator.StalenessInput("NASDAQ", NOW.minusSeconds(3600).getEpochSecond()));

        ModulationResult result = modulator.evaluate(null, null);

        assertTrue(result.applied());
        assertEquals(1.0, result.factor(), 1e-9);
    }

    @Test
    @DisplayName("aucune entrée => applied=true, facteur neutre 1.0, jamais d'exception")
    void noInputs_neutralFactor() {
        StalenessModulator modulator = new StalenessModulator(NOW, STALE_THRESHOLD_HOURS);

        ModulationResult result = modulator.evaluate(null, null);

        assertTrue(result.applied());
        assertEquals(1.0, result.factor(), 1e-9);
    }
}
