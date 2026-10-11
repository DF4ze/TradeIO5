package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie l'adaptateur {@link StrategyConfidenceModulator} (étude "unification-confidence-modulator",
 * 2026-07-16, §2) : seul adaptateur des trois qui a réellement besoin de {@code context} (pour
 * évaluer la {@code Strategy} sous-jacente), et le seul qui expose le {@code StrategySignal} brut via
 * {@link StrategyConfidenceModulator#getLastSignal()} pour la traçabilité dans
 * {@code AbstractMarketOpinion}. Couverture indirecte existante via {@code AbstractMarketOpinionTest},
 * mais aucun test dédié à cette classe elle-même avant ce lot.
 */
@DisplayName("StrategyConfidenceModulator - adaptateur Strategy CONFIDENCE_MODULATOR")
class StrategyConfidenceModulatorTest {

    @Test
    @DisplayName("signal valide => applied=true, facteur = computeConfidenceModulationFactor(score)")
    void validSignal_appliedTrue_factorMatchesHelper() {
        StrategySignal signal = StrategySignal.builder()
                .valid(true).score(-0.8).confidence(1.0).type(SignalType.BEARISH).strategyName("modulator").build();
        Strategy strategy = mock(Strategy.class);
        when(strategy.evaluate(any(), any())).thenReturn(signal);

        StrategyConfidenceModulator modulator = new StrategyConfidenceModulator(strategy, new StrategyParameters());
        ModulationResult result = modulator.evaluate(dummyContext(), null);

        assertTrue(result.applied());
        assertEquals(MarketOpinionHelper.computeConfidenceModulationFactor(-0.8), result.factor(), 1e-9);
        assertTrue(result.reason().contains("-0.8"));
    }

    @Test
    @DisplayName("signal invalide => applied=false, facteur neutre 1.0, raison = celle du signal")
    void invalidSignal_notApplied_neutralFactor() {
        StrategySignal signal = StrategySignal.notValid("brokenModulator", "missing OPEN_INTEREST");
        Strategy strategy = mock(Strategy.class);
        when(strategy.evaluate(any(), any())).thenReturn(signal);

        StrategyConfidenceModulator modulator = new StrategyConfidenceModulator(strategy, new StrategyParameters());
        ModulationResult result = modulator.evaluate(dummyContext(), null);

        assertFalse(result.applied());
        assertEquals(1.0, result.factor(), 1e-9);
        assertEquals("missing OPEN_INTEREST", result.reason());
    }

    @Test
    @DisplayName("getLastSignal() expose le StrategySignal brut de la dernière évaluation, jamais ré-évaluée")
    void getLastSignal_exposesRawSignal_evaluatedOnlyOnce() {
        StrategySignal signal = StrategySignal.builder()
                .valid(true).score(0.3).confidence(1.0).type(SignalType.NEUTRAL).strategyName("modulator").build();
        Strategy strategy = mock(Strategy.class);
        when(strategy.evaluate(any(), any())).thenReturn(signal);

        StrategyConfidenceModulator modulator = new StrategyConfidenceModulator(strategy, new StrategyParameters());

        assertNull(modulator.getLastSignal(), "aucun signal tant qu'evaluate() n'a pas été appelé");

        modulator.evaluate(dummyContext(), null);

        assertSame(signal, modulator.getLastSignal());
        assertSame(strategy, modulator.getStrategy());
        verify(strategy, times(1)).evaluate(any(), any());
    }

    private static OpinionContext dummyContext() {
        DomainClock clock = Instant::now;
        MarketContext marketContext = new MarketContext("BTCUSDT", null, clock, Map.of(), new HashMap<>());
        return new OpinionContext(null, null, marketContext, new HashMap<>(), clock);
    }
}
