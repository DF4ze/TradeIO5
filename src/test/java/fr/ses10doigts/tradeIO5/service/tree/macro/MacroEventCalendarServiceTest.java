package fr.ses10doigts.tradeIO5.service.tree.macro;

import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Macro - MacroEventCalendarService")
class MacroEventCalendarServiceTest {

    private static final Instant FOMC_TIME = Instant.parse("2026-07-06T14:00:00Z");

    @Test
    @DisplayName("dedupe() fusionne un même événement FOMC rapporté par les deux sources, en gardant la version Finnhub")
    void dedupe_mergesSameFomcEventAcrossSources_keepingFinnhubVersion() {
        MacroEvent finnhubFomc = MacroEvent.builder()
                .title("FOMC Interest Rate Decision")
                .country("US")
                .dateTime(FOMC_TIME)
                .impact(MacroEventImpact.HIGH)
                .source(MacroEventSource.FINNHUB)
                .actual("5.50%")
                .forecast("5.50%")
                .previous("5.25%")
                .build();

        // Même événement côté ForexFactory : titre légèrement différent, horodatage à 3 minutes
        // près (arrondi différent selon la source), pas d'"actual" disponible.
        MacroEvent forexFactoryFomc = MacroEvent.builder()
                .title("FOMC Rate Decision")
                .country("US")
                .dateTime(FOMC_TIME.plus(3, ChronoUnit.MINUTES))
                .impact(MacroEventImpact.HIGH)
                .source(MacroEventSource.FOREXFACTORY)
                .forecast("5.50%")
                .previous("5.25%")
                .build();

        List<MacroEvent> result = MacroEventCalendarService.dedupe(List.of(finnhubFomc), List.of(forexFactoryFomc));

        assertEquals(1, result.size());
        assertEquals(MacroEventSource.FINNHUB, result.getFirst().getSource());
        assertEquals("5.50%", result.getFirst().getActual());
    }

    @Test
    @DisplayName("dedupe() garde les deux événements quand ils ne se recoupent pas (pays différent)")
    void dedupe_keepsBothEvents_whenNotOverlapping() {
        MacroEvent finnhubEvent = MacroEvent.builder()
                .title("CPI m/m")
                .country("US")
                .dateTime(FOMC_TIME)
                .impact(MacroEventImpact.HIGH)
                .source(MacroEventSource.FINNHUB)
                .build();

        MacroEvent forexFactoryEvent = MacroEvent.builder()
                .title("ISM Services PMI")
                .country("EUR")
                .dateTime(FOMC_TIME)
                .impact(MacroEventImpact.HIGH)
                .source(MacroEventSource.FOREXFACTORY)
                .build();

        List<MacroEvent> result = MacroEventCalendarService.dedupe(List.of(finnhubEvent), List.of(forexFactoryEvent));

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("dedupe() garde les deux événements quand ils sont trop éloignés dans le temps")
    void dedupe_keepsBothEvents_whenTooFarApartInTime() {
        MacroEvent finnhubEvent = MacroEvent.builder()
                .title("FOMC Interest Rate Decision")
                .country("US")
                .dateTime(FOMC_TIME)
                .impact(MacroEventImpact.HIGH)
                .source(MacroEventSource.FINNHUB)
                .build();

        MacroEvent forexFactoryEvent = MacroEvent.builder()
                .title("FOMC Interest Rate Decision")
                .country("US")
                .dateTime(FOMC_TIME.plus(2, ChronoUnit.HOURS))
                .impact(MacroEventImpact.HIGH)
                .source(MacroEventSource.FOREXFACTORY)
                .build();

        List<MacroEvent> result = MacroEventCalendarService.dedupe(List.of(finnhubEvent), List.of(forexFactoryEvent));

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("hasRiskEvent() détecte un événement HIGH dans la fenêtre")
    void hasRiskEvent_detectsHighImpactEventInWindow() {
        MacroEvent event = MacroEvent.builder()
                .title("FOMC")
                .country("US")
                .dateTime(FOMC_TIME)
                .impact(MacroEventImpact.HIGH)
                .source(MacroEventSource.FINNHUB)
                .build();

        assertTrue(MacroEventCalendarService.hasRiskEvent(List.of(event), MacroEventImpact.HIGH));
        assertTrue(MacroEventCalendarService.hasRiskEvent(List.of(event), MacroEventImpact.MEDIUM));
    }

    @Test
    @DisplayName("hasRiskEvent() retourne false quand aucun événement n'atteint le seuil d'impact")
    void hasRiskEvent_returnsFalse_whenNoEventReachesThreshold() {
        MacroEvent event = MacroEvent.builder()
                .title("Minor release")
                .country("US")
                .dateTime(FOMC_TIME)
                .impact(MacroEventImpact.LOW)
                .source(MacroEventSource.FINNHUB)
                .build();

        assertFalse(MacroEventCalendarService.hasRiskEvent(List.of(event), MacroEventImpact.HIGH));
    }

    @Test
    @DisplayName("hasRiskEvent() retourne false sur une liste vide")
    void hasRiskEvent_returnsFalse_onEmptyList() {
        assertFalse(MacroEventCalendarService.hasRiskEvent(List.of(), MacroEventImpact.LOW));
    }
}
