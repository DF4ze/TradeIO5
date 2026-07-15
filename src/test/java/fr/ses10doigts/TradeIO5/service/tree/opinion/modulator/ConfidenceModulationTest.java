package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie la boucle commune {@link ConfidenceModulation} (étude "unification-confidence-modulator",
 * 2026-07-16, §2) : aucun test dédié n'existait pour ce package avant ce lot, seulement une
 * couverture indirecte via {@code AbstractMarketOpinionTest}/{@code MacroMarketOpinionTest}.
 */
@DisplayName("ConfidenceModulation - boucle commune d'application des ConfidenceModulator")
class ConfidenceModulationTest {

    @Test
    @DisplayName("evaluateAll évalue chaque modulateur exactement une fois, dans l'ordre fourni")
    void evaluateAll_evaluatesEachModulatorExactlyOnce_inOrder() {
        ConfidenceModulator first = mock(ConfidenceModulator.class);
        ConfidenceModulator second = mock(ConfidenceModulator.class);
        ModulationResult firstResult = new ModulationResult(true, 0.8, "first");
        ModulationResult secondResult = new ModulationResult(true, 0.9, "second");
        when(first.evaluate(any(), any())).thenReturn(firstResult);
        when(second.evaluate(any(), any())).thenReturn(secondResult);

        List<ModulationResult> results = ConfidenceModulation.evaluateAll(
                List.of(first, second), dummyContext(), dummyParameters());

        assertEquals(List.of(firstResult, secondResult), results);
        verify(first, times(1)).evaluate(any(), any());
        verify(second, times(1)).evaluate(any(), any());
    }

    @Test
    @DisplayName("combinedFactor : produit des facteurs 'applied', jamais 1.0 forcé si tous atténuent")
    void combinedFactor_multipliesOnlyAppliedResults() {
        List<ModulationResult> results = List.of(
                new ModulationResult(true, 0.5, "a"),
                new ModulationResult(true, 0.8, "b"));

        double factor = ConfidenceModulation.combinedFactor(results);

        assertEquals(0.4, factor, 1e-9);
    }

    @Test
    @DisplayName("combinedFactor ignore les résultats non appliqués (applied=false)")
    void combinedFactor_ignoresNonAppliedResults() {
        List<ModulationResult> results = List.of(
                new ModulationResult(false, 0.1, "donnée manquante, ignorée"),
                new ModulationResult(true, 0.7, "b"));

        double factor = ConfidenceModulation.combinedFactor(results);

        assertEquals(0.7, factor, 1e-9,
                "le facteur du résultat non-applied (0.1) ne doit jamais entrer dans le produit");
    }

    @Test
    @DisplayName("liste vide ou entièrement non-applied => facteur neutre 1.0")
    void combinedFactor_emptyOrAllNonApplied_returnsNeutralFactor() {
        assertEquals(1.0, ConfidenceModulation.combinedFactor(List.of()), 1e-9);
        assertEquals(1.0, ConfidenceModulation.combinedFactor(
                List.of(new ModulationResult(false, 0.2, "ignoré"))), 1e-9);
    }

    @Test
    @DisplayName("combinedFactor reste toujours dans ]0,1] pour des facteurs individuels valides")
    void combinedFactor_staysInValidRange() {
        double factor = ConfidenceModulation.combinedFactor(List.of(
                new ModulationResult(true, 0.5, "a"),
                new ModulationResult(true, 0.5, "b"),
                new ModulationResult(true, 0.5, "c")));

        assertTrue(factor > 0.0 && factor <= 1.0);
        assertEquals(0.125, factor, 1e-9);
    }

    private static OpinionContext dummyContext() {
        return null; // ConfidenceModulation.evaluateAll ne fait que transmettre context/parameters tels quels
    }

    private static MarketOpinionParameters dummyParameters() {
        return null;
    }
}
