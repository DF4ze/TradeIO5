package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vérifie le mécanisme introduit le 2026-07-15 pour résoudre la dette documentée dans
 * {@code MovementQualificationStrategy}/{@code OrderFlowStrategy} (Strategies qui qualifient la
 * fiabilité d'un mouvement plutôt que de voter sur sa direction, mais agrégées jusqu'ici comme des
 * Strategies {@code ENTRY} classiques) : {@link AbstractMarketOpinion#decide} sépare désormais les
 * {@code StrategyKey} de type {@link StrategyType#CONFIDENCE_MODULATOR} des Strategies
 * {@code ENTRY}/{@code EXIT}, et n'utilise leur score que pour atténuer la confidence finale
 * (jamais le score directionnel), via {@link MarketOpinionHelper#computeConfidenceModulationFactor}.
 * <p>
 * Utilise des {@link Strategy} mockées (pas de vrai indicateur/réseau) pour isoler ce mécanisme de
 * la logique métier de {@code MovementQualificationStrategy}/{@code OrderFlowStrategy} elles-mêmes
 * (déjà couvertes par leurs tests dédiés).
 */
@DisplayName("AbstractMarketOpinion - séparation ENTRY / CONFIDENCE_MODULATOR")
class AbstractMarketOpinionTest {

    @Test
    @DisplayName("une Strategy CONFIDENCE_MODULATOR n'entre jamais dans la somme StrategyAggregator, seulement dans la confidence")
    void confidenceModulator_neverJoinsAggregatedScore_onlyAttenuatesConfidence() {
        // StrategyAggregator.aggregate() recalcule toujours la confidence à partir du score total
        // via MarketOpinionHelper.scoreToConfidenceAndSignalType (le champ StrategySignal.confidence
        // individuel n'est pas lu) : la confidence "de base" ici est donc celle de 0.8, pas 0.9.
        Strategy entryStrategy = mockStrategy(StrategyType.ENTRY,
                StrategySignal.builder().valid(true).score(0.8).confidence(0.9).type(SignalType.BULLISH).strategyName("entry").build());

        Strategy modulatorStrategy = mockStrategy(StrategyType.CONFIDENCE_MODULATOR,
                StrategySignal.builder().valid(true).score(-1.0).confidence(1.0).type(SignalType.BEARISH).strategyName("modulator").build());

        MarketOpinionParameters params = twoStrategyParams(entryStrategy, modulatorStrategy);

        CapturingOpinion opinion = new CapturingOpinion();
        opinion.decide(dummyContext(), params);

        AggregatedStrategySignal result = opinion.captured;
        assertNotNull(result, "interpretSignals() doit avoir été appelé");

        // Le score reste celui de la seule Strategy ENTRY : le -1.0 du modulateur ne s'additionne
        // jamais dedans (contrairement à l'ancien comportement où les deux étaient sommés).
        assertEquals(0.8, result.getScore(), 1e-9);

        // confidence atténuée par le facteur du modulateur, jamais amplifiée ni mise à 0 brutalement.
        double baseConfidence = MarketOpinionHelper.scoreToConfidenceAndSignalType(0.8).confidence;
        double expectedFactor = MarketOpinionHelper.computeConfidenceModulationFactor(-1.0);
        assertEquals(baseConfidence * expectedFactor, result.getConfidence(), 1e-9);
        assertEquals(0.5, expectedFactor, 1e-9, "score modulateur -1.0 => facteur plancher 0.5 (jamais 0)");

        // Le signal du modulateur reste tracé (traçabilité), même s'il ne pèse pas dans le score.
        assertEquals(2, result.getSignals().size());
    }

    @Test
    @DisplayName("un modulateur invalide (donnée manquante) n'invalide pas les Strategies ENTRY (pas de contagion all-or-nothing)")
    void invalidModulator_doesNotZeroOutEntryStrategies() {
        Strategy entryStrategy = mockStrategy(StrategyType.ENTRY,
                StrategySignal.builder().valid(true).score(0.8).confidence(0.9).type(SignalType.BULLISH).strategyName("entry").build());

        Strategy modulatorStrategy = mockStrategy(StrategyType.CONFIDENCE_MODULATOR,
                StrategySignal.notValid("brokenModulator", "missing OPEN_INTEREST"));
        when(modulatorStrategy.getName()).thenReturn("brokenModulator");

        MarketOpinionParameters params = twoStrategyParams(entryStrategy, modulatorStrategy);

        CapturingOpinion opinion = new CapturingOpinion();
        opinion.decide(dummyContext(), params);

        AggregatedStrategySignal result = opinion.captured;
        assertNotNull(result);

        // Avec l'ancien comportement (StrategyAggregator.aggregate mélangeant tout), une seule
        // Strategy invalide mettait totalScore/confidence à 0 pour TOUTES les Strategies. Ici,
        // un modulateur invalide ne doit avoir aucun effet sur le résultat de l'ENTRY.
        double baseConfidence = MarketOpinionHelper.scoreToConfidenceAndSignalType(0.8).confidence;
        assertEquals(0.8, result.getScore(), 1e-9);
        assertEquals(baseConfidence, result.getConfidence(), 1e-9);
    }

    @Test
    @DisplayName("aucune Strategy CONFIDENCE_MODULATOR déclarée -> comportement inchangé (pas de facteur appliqué)")
    void noModulator_behavesLikePlainAggregation() {
        Strategy entryStrategy = mockStrategy(StrategyType.ENTRY,
                StrategySignal.builder().valid(true).score(0.5).confidence(0.6).type(SignalType.BULLISH).strategyName("entry").build());

        MarketOpinionParameters params = MarketOpinionParameters.builder()
                .strategies(List.of(new StrategyKey(entryStrategy, new StrategyParameters())))
                .build();

        CapturingOpinion opinion = new CapturingOpinion();
        opinion.decide(dummyContext(), params);

        double baseConfidence = MarketOpinionHelper.scoreToConfidenceAndSignalType(0.5).confidence;
        assertEquals(0.5, opinion.captured.getScore(), 1e-9);
        assertEquals(baseConfidence, opinion.captured.getConfidence(), 1e-9);
        assertEquals(1, opinion.captured.getSignals().size());
    }

    private static Strategy mockStrategy(StrategyType type, StrategySignal signal) {
        Strategy strategy = mock(Strategy.class);
        when(strategy.getType()).thenReturn(Set.of(type));
        when(strategy.evaluate(any(), any())).thenReturn(signal);
        return strategy;
    }

    private static MarketOpinionParameters twoStrategyParams(Strategy first, Strategy second) {
        return MarketOpinionParameters.builder()
                .strategies(List.of(
                        new StrategyKey(first, new StrategyParameters()),
                        new StrategyKey(second, new StrategyParameters())
                ))
                .build();
    }

    private static OpinionContext dummyContext() {
        DomainClock clock = Instant::now;
        MarketContext marketContext = new MarketContext("BTCUSDT", null, clock, Map.of(), new HashMap<>());
        return new OpinionContext(null, null, marketContext, new HashMap<>(), clock);
    }

    /** Sous-classe minimale : capture l'{@link AggregatedStrategySignal} déjà modulé, ne publie rien. */
    private static class CapturingOpinion extends AbstractMarketOpinion {
        AggregatedStrategySignal captured;

        @Override
        public OpinionScope getScope() {
            return OpinionScope.LOCAL;
        }

        @Override
        protected void interpretSignals(
                OpinionContext context, MarketOpinionParameters parameters, AggregatedStrategySignal aggregatedSignal) {
            this.captured = aggregatedSignal;
        }
    }
}
