package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.DxyIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.Sp500Indicator;
import fr.ses10doigts.tradeIO5.service.tree.macro.MacroEventCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie {@link MacroMarketOpinion} (étude "nouvelles-opinions-indicateurs-non-branches" §2) :
 * premier consommateur du scope {@code MACRO}, jusqu'ici déclaré mais inutilisé.
 */
@DisplayName("MacroMarketOpinion - DXY + SP500 + NASDAQ (risk appetite)")
class MacroMarketOpinionTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Nested
    @DisplayName("computeRiskAppetiteScore (logique pure)")
    class ComputeRiskAppetiteScoreTest {

        @Test
        @DisplayName("dollar en baisse + actions en hausse => score positif (risk-on)")
        void riskOn_positiveScore() {
            double score = MacroMarketOpinion.computeRiskAppetiteScore(
                    -0.005, 0.01, 0.01,
                    0.005, 0.01,
                    0.3, 0.3, 0.4);

            assertTrue(score > 0.0);
        }

        @Test
        @DisplayName("dollar en hausse + actions en baisse => score négatif (risk-off)")
        void riskOff_negativeScore() {
            double score = MacroMarketOpinion.computeRiskAppetiteScore(
                    0.005, -0.01, -0.01,
                    0.005, 0.01,
                    0.3, 0.3, 0.4);

            assertTrue(score < 0.0);
        }

        @Test
        @DisplayName("toutes variations nulles => score neutre 0.0")
        void noChange_neutralScore() {
            double score = MacroMarketOpinion.computeRiskAppetiteScore(
                    0.0, 0.0, 0.0, 0.005, 0.01, 0.3, 0.3, 0.4);

            assertEquals(0.0, score, 1e-9);
        }

        @Test
        @DisplayName("score toujours borné à [-1,1] même avec des variations extrêmes")
        void score_alwaysClamped() {
            double score = MacroMarketOpinion.computeRiskAppetiteScore(
                    -0.5, 0.5, 0.5, 0.005, 0.01, 0.3, 0.3, 0.4);

            assertEquals(1.0, score, 1e-9);
        }
    }

    @Nested
    @DisplayName("decide() - intégration")
    class DecideTest {

        private IndicatorEngine indicatorEngine;
        private EventBus eventBus;
        private MacroEventCalendarService calendarService;
        private MacroMarketOpinion opinion;

        @BeforeEach
        void setUp() throws Exception {
            indicatorEngine = mock(IndicatorEngine.class);
            IndicatorCredentialResolver credentialResolver = mock(IndicatorCredentialResolver.class);
            eventBus = mock(EventBus.class);
            // Mockito retourne false par défaut (comportement non stubbé) : hors fenêtre à risque macro,
            // donc aucune atténuation supplémentaire pour les tests existants qui ne stubbent pas ce mock.
            calendarService = mock(MacroEventCalendarService.class);

            opinion = new MacroMarketOpinion(indicatorEngine, credentialResolver, calendarService);
            Field field = MacroMarketOpinion.class.getDeclaredField("eventBus");
            field.setAccessible(true);
            field.set(opinion, eventBus);
        }

        @Test
        @DisplayName("getScope() retourne MACRO")
        void getScope_returnsMacro() {
            assertEquals(OpinionScope.MACRO, opinion.getScope());
        }

        @Test
        @DisplayName("dollar en baisse + actions fraîches en hausse => OpinionEvent BULLISH publié, scope MACRO, sans symbole")
        void decide_publishesBullishEvent_onRiskOn() {
            mockIndicator(IndicatorType.DXY, 99.5, 100.0, null);
            mockIndicator(IndicatorType.SP500, 5700.0, 5600.0, NOW.getEpochSecond());
            mockIndicator(IndicatorType.NASDAQ, 18400.0, 18200.0, NOW.getEpochSecond());

            opinion.decide(context(), MarketOpinionParameters.builder().build());

            ArgumentCaptor<OpinionEvent> captor = ArgumentCaptor.forClass(OpinionEvent.class);
            verify(eventBus).publish(captor.capture());

            OpinionEvent event = captor.getValue();
            assertEquals(OpinionScope.MACRO, event.getScope());
            assertTrue(event.getSymbol().isEmpty());
            assertTrue(event.getScore() > 0.0);
            assertEquals(SignalType.BULLISH, event.getWeightedSignal());
        }

        @Test
        @DisplayName("aucun OpinionEvent publié quand DXY est invalide")
        void decide_publishesNothing_whenDxyInvalid() {
            when(indicatorEngine.execute(any(), argThatType(IndicatorType.DXY)))
                    .thenReturn(IndicatorSnapshot.builder().result(IndicatorResult.invalid()).build());
            mockIndicator(IndicatorType.SP500, 5700.0, 5600.0, NOW.getEpochSecond());
            mockIndicator(IndicatorType.NASDAQ, 18400.0, 18200.0, NOW.getEpochSecond());

            opinion.decide(context(), MarketOpinionParameters.builder().build());

            verify(eventBus, never()).publish(any());
        }

        @Test
        @DisplayName("aucun OpinionEvent publié quand values.previous manque (delta non calculable)")
        void decide_publishesNothing_whenPreviousMissing() {
            mockIndicator(IndicatorType.DXY, 99.5, null, null);
            mockIndicator(IndicatorType.SP500, 5700.0, 5600.0, NOW.getEpochSecond());
            mockIndicator(IndicatorType.NASDAQ, 18400.0, 18200.0, NOW.getEpochSecond());

            opinion.decide(context(), MarketOpinionParameters.builder().build());

            verify(eventBus, never()).publish(any());
        }

        @Test
        @DisplayName("quotes actions figées (week-end) => confidence atténuée par rapport à des quotes fraîches")
        void decide_dampensConfidence_whenQuotesStale() throws Exception {
            mockIndicator(IndicatorType.DXY, 99.5, 100.0, null);
            mockIndicator(IndicatorType.SP500, 5700.0, 5600.0, NOW.getEpochSecond());
            mockIndicator(IndicatorType.NASDAQ, 18400.0, 18200.0, NOW.getEpochSecond());
            opinion.decide(context(), MarketOpinionParameters.builder().build());
            ArgumentCaptor<OpinionEvent> freshCaptor = ArgumentCaptor.forClass(OpinionEvent.class);
            verify(eventBus).publish(freshCaptor.capture());

            EventBus staleEventBus = mock(EventBus.class);
            IndicatorEngine staleEngine = mock(IndicatorEngine.class);
            mockIndicatorOn(staleEngine, IndicatorType.DXY, 99.5, 100.0, null);
            long fortyHoursAgo = NOW.minusSeconds(60L * 60 * 40).getEpochSecond();
            mockIndicatorOn(staleEngine, IndicatorType.SP500, 5700.0, 5600.0, fortyHoursAgo);
            mockIndicatorOn(staleEngine, IndicatorType.NASDAQ, 18400.0, 18200.0, fortyHoursAgo);
            MacroMarketOpinion staleOpinion = newOpinion(staleEngine, staleEventBus);

            staleOpinion.decide(context(), MarketOpinionParameters.builder().build());
            ArgumentCaptor<OpinionEvent> staleCaptor = ArgumentCaptor.forClass(OpinionEvent.class);
            verify(staleEventBus).publish(staleCaptor.capture());

            assertTrue(staleCaptor.getValue().getConfidence() < freshCaptor.getValue().getConfidence());
        }

        @Test
        @DisplayName("Palier 3, étape 8 : fenêtre à risque macro active => confidence atténuée (0.5 par défaut) "
                + "vs même scénario sans fenêtre à risque, signal directionnel inchangé")
        void decide_dampensConfidence_whenMacroRiskWindowActive() throws Exception {
            mockIndicator(IndicatorType.DXY, 99.5, 100.0, null);
            mockIndicator(IndicatorType.SP500, 5700.0, 5600.0, NOW.getEpochSecond());
            mockIndicator(IndicatorType.NASDAQ, 18400.0, 18200.0, NOW.getEpochSecond());
            // Référence : calendarService (mock du setUp) retourne false par défaut => pas de fenêtre
            // à risque active.
            opinion.decide(context(), MarketOpinionParameters.builder().build());
            ArgumentCaptor<OpinionEvent> noRiskCaptor = ArgumentCaptor.forClass(OpinionEvent.class);
            verify(eventBus).publish(noRiskCaptor.capture());

            EventBus riskEventBus = mock(EventBus.class);
            IndicatorEngine riskEngine = mock(IndicatorEngine.class);
            MacroEventCalendarService riskCalendarService = mock(MacroEventCalendarService.class);
            when(riskCalendarService.isWithinRiskWindow(any(), any(), any())).thenReturn(true);
            mockIndicatorOn(riskEngine, IndicatorType.DXY, 99.5, 100.0, null);
            mockIndicatorOn(riskEngine, IndicatorType.SP500, 5700.0, 5600.0, NOW.getEpochSecond());
            mockIndicatorOn(riskEngine, IndicatorType.NASDAQ, 18400.0, 18200.0, NOW.getEpochSecond());
            MacroMarketOpinion riskOpinion = newOpinion(riskEngine, riskEventBus, riskCalendarService);

            riskOpinion.decide(context(), MarketOpinionParameters.builder().build());
            ArgumentCaptor<OpinionEvent> riskCaptor = ArgumentCaptor.forClass(OpinionEvent.class);
            verify(riskEventBus).publish(riskCaptor.capture());

            assertEquals(0.5, riskCaptor.getValue().getConfidence() / noRiskCaptor.getValue().getConfidence(), 1e-9,
                    "une fenêtre à risque macro active doit atténuer la confidence par le facteur par défaut (0.5)");
            assertEquals(noRiskCaptor.getValue().getWeightedSignal(), riskCaptor.getValue().getWeightedSignal(),
                    "le signal directionnel ne doit jamais être modifié par le modulateur de risque macro");
        }

        @Test
        @DisplayName("Fix 2026-08-14 (Palier 3, étape 7, décision 6) : un symbole non nul dans le contexte "
                + "n'est jamais reporté sur l'OpinionEvent publié (Opinion MACRO, pas de symbole par construction)")
        void decide_neverPropagatesSymbol_evenWhenContextCarriesOne() {
            mockIndicator(IndicatorType.DXY, 99.5, 100.0, null);
            mockIndicator(IndicatorType.SP500, 5700.0, 5600.0, NOW.getEpochSecond());
            mockIndicator(IndicatorType.NASDAQ, 18400.0, 18200.0, NOW.getEpochSecond());

            MarketContext mc = new MarketContext("BTC", BigDecimal.ZERO, new FixedDomainClock(NOW), Map.of(), Map.of());
            OpinionContext contextWithSymbol = new OpinionContext(null, null, mc, Map.of(), new FixedDomainClock(NOW));

            opinion.decide(contextWithSymbol, MarketOpinionParameters.builder().build());

            ArgumentCaptor<OpinionEvent> captor = ArgumentCaptor.forClass(OpinionEvent.class);
            verify(eventBus).publish(captor.capture());

            assertTrue(captor.getValue().getSymbol().isEmpty(),
                    "MACRO ne doit jamais porter de symbole, même si le contexte en fournit un (ex: appelé par "
                            + "l'orchestrateur, Palier 3 étape 7)");
        }

        private MacroMarketOpinion newOpinion(IndicatorEngine engine, EventBus bus) throws Exception {
            return newOpinion(engine, bus, mock(MacroEventCalendarService.class));
        }

        private MacroMarketOpinion newOpinion(IndicatorEngine engine, EventBus bus, MacroEventCalendarService macroCalendarService) throws Exception {
            MacroMarketOpinion o = new MacroMarketOpinion(engine, mock(IndicatorCredentialResolver.class), macroCalendarService);
            Field field = MacroMarketOpinion.class.getDeclaredField("eventBus");
            field.setAccessible(true);
            field.set(o, bus);
            return o;
        }

        private void mockIndicator(IndicatorType type, double current, Double previous, Long lastTradeTime) {
            mockIndicatorOn(indicatorEngine, type, current, previous, lastTradeTime);
        }

        private void mockIndicatorOn(IndicatorEngine engine, IndicatorType type, double current, Double previous, Long lastTradeTime) {
            Map<String, Double> values = new java.util.HashMap<>();
            String previousKey = type == IndicatorType.DXY ? DxyIndicator.V_PREVIOUS : Sp500Indicator.V_PREVIOUS;
            if (previous != null) {
                values.put(previousKey, previous);
            }
            if (lastTradeTime != null) {
                values.put(Sp500Indicator.V_LAST_TRADE_TIME, lastTradeTime.doubleValue());
            }
            IndicatorResult.IndicatorResultBuilder builder = IndicatorResult.builder().valid(true).value(current);
            if (!values.isEmpty()) {
                builder.values(values);
            }
            when(engine.execute(any(), argThatType(type)))
                    .thenReturn(IndicatorSnapshot.builder().result(builder.build()).build());
        }

        private OpinionContext context() {
            MarketContext mc = new MarketContext(null, BigDecimal.ZERO, new FixedDomainClock(NOW), Map.of(), Map.of());
            return new OpinionContext(null, null, mc, Map.of(), new FixedDomainClock(NOW));
        }
    }

    private static IndicatorParameters argThatType(IndicatorType type) {
        return org.mockito.ArgumentMatchers.argThat(p -> p != null && p.getIndicatorType() == type);
    }
}
