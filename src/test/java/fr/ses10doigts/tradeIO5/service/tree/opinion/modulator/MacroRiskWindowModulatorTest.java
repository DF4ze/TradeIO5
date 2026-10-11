package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.service.tree.macro.MacroEventCalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie l'adaptateur {@link MacroRiskWindowModulator} (Palier 3, étape 8) :
 * {@link MacroEventCalendarService#isWithinRiskWindow} mocké, jamais de vrai appel réseau
 * Finnhub/ForexFactory dans ce test unitaire.
 */
@DisplayName("MacroRiskWindowModulator - adaptateur de isWithinRiskWindow")
class MacroRiskWindowModulatorTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(2);
    private static final MacroEventImpact MIN_IMPACT = MacroEventImpact.HIGH;
    private static final double DAMPENING_FACTOR = 0.5;

    @Test
    @DisplayName("hors fenêtre à risque => applied=true, factor=1.0")
    void outsideRiskWindow_appliedTrue_neutralFactor() {
        MacroEventCalendarService calendarService = mock(MacroEventCalendarService.class);
        when(calendarService.isWithinRiskWindow(NOW, WINDOW, MIN_IMPACT)).thenReturn(false);

        MacroRiskWindowModulator modulator = new MacroRiskWindowModulator(
                calendarService, NOW, WINDOW, MIN_IMPACT, DAMPENING_FACTOR);

        ModulationResult result = modulator.evaluate(null, null);

        assertTrue(result.applied());
        assertEquals(1.0, result.factor(), 1e-9);
    }

    @Test
    @DisplayName("dans la fenêtre à risque => applied=true, factor=dampeningFactor (jamais 1.0, jamais 0)")
    void withinRiskWindow_appliedTrue_dampenedFactor() {
        MacroEventCalendarService calendarService = mock(MacroEventCalendarService.class);
        when(calendarService.isWithinRiskWindow(NOW, WINDOW, MIN_IMPACT)).thenReturn(true);

        MacroRiskWindowModulator modulator = new MacroRiskWindowModulator(
                calendarService, NOW, WINDOW, MIN_IMPACT, DAMPENING_FACTOR);

        ModulationResult result = modulator.evaluate(null, null);

        assertTrue(result.applied());
        assertEquals(DAMPENING_FACTOR, result.factor(), 1e-9);
        assertTrue(result.factor() > 0.0 && result.factor() < 1.0);
    }

    @Test
    @DisplayName("now/window/minImpact transmis au constructeur sont bien ceux passés tels quels à isWithinRiskWindow")
    void evaluate_passesConstructorArguments_untouched() {
        MacroEventCalendarService calendarService = mock(MacroEventCalendarService.class);
        when(calendarService.isWithinRiskWindow(NOW, WINDOW, MIN_IMPACT)).thenReturn(false);

        MacroRiskWindowModulator modulator = new MacroRiskWindowModulator(
                calendarService, NOW, WINDOW, MIN_IMPACT, DAMPENING_FACTOR);
        modulator.evaluate(null, null);

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Duration> windowCaptor = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<MacroEventImpact> minImpactCaptor = ArgumentCaptor.forClass(MacroEventImpact.class);
        verify(calendarService).isWithinRiskWindow(nowCaptor.capture(), windowCaptor.capture(), minImpactCaptor.capture());

        assertEquals(NOW, nowCaptor.getValue());
        assertEquals(WINDOW, windowCaptor.getValue());
        assertEquals(MIN_IMPACT, minImpactCaptor.getValue());
    }
}
