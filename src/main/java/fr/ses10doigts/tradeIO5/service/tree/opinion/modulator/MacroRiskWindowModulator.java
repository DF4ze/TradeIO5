package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.service.tree.macro.MacroEventCalendarService;

import java.time.Duration;
import java.time.Instant;

/**
 * Adaptateur {@link ConfidenceModulator} pour {@link MacroEventCalendarService#isWithinRiskWindow}
 * (Palier 3, étape 8) : atténue la confidence d'une Opinion d'ambiance (GLOBAL/MACRO) pendant une
 * fenêtre autour d'un événement macro à fort impact (FOMC/NFP/CPI...), sans jamais l'annuler
 * (contrat {@link ModulationResult}, même règle que tout autre modulateur). Contrairement à
 * {@link StalenessModulator}/{@link SentimentShiftModulator}, l'appel réseau sous-jacent
 * (Finnhub/ForexFactory) a lieu dans {@link #evaluate}, pas à la construction — cf. javadoc du
 * prompt d'implémentation de cette étape.
 */
public class MacroRiskWindowModulator implements ConfidenceModulator {

    private final MacroEventCalendarService calendarService;
    private final Instant now;
    private final Duration window;
    private final MacroEventImpact minImpact;
    private final double dampeningFactor;

    public MacroRiskWindowModulator(
            MacroEventCalendarService calendarService, Instant now, Duration window,
            MacroEventImpact minImpact, double dampeningFactor) {
        this.calendarService = calendarService;
        this.now = now;
        this.window = window;
        this.minImpact = minImpact;
        this.dampeningFactor = dampeningFactor;
    }

    @Override
    public ModulationResult evaluate(OpinionContext context, MarketOpinionParameters parameters) {
        boolean atRisk = calendarService.isWithinRiskWindow(now, window, minImpact);
        if (!atRisk) {
            return new ModulationResult(true, 1.0, "hors fenêtre à risque macro");
        }
        String reason = String.format(
                "fenêtre à risque macro active (impact >= %s, +/- %s)", minImpact, window);
        return new ModulationResult(true, dampeningFactor, reason);
    }
}
