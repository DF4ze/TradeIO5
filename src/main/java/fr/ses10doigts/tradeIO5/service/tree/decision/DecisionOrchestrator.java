package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisFacade;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.EtfFlowConfidenceStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrateur du cycle Opinion → Scenario → Decision (Palier 3, étape 7,
 * docs/etudes/etude-branchement-persistance-decision-engine.md §E point 6). Calcule les Opinions du
 * périmètre DCA fixe ({@link #TRACKED_ASSETS}) une fois par cycle, puis les propage à chaque owner
 * actif via {@link ScenarioEngine#onMarketOpinion(OpinionSignal, ScenarioContext)} — la cascade
 * Opinion → Scenario → Decision se déclenche ensuite en interne (abonnement bus déjà en place côté
 * {@link DecisionEngine}), cette classe n'appelle jamais {@code DecisionEngine} directement.
 * <p>
 * Déclencheurs pour ce lot : cron désactivé par défaut ({@code DecisionOrchestratorJob}) + endpoint
 * admin ({@code DecisionOrchestratorAdminController}), même patron qu'étapes 4/6. Le bouton
 * utilisateur ("à la demande") et l'auto-sync à la connexion restent postposés à une étape
 * ultérieure — ils pourront appeler {@link #runCycle()} directement sans changer sa forme.
 */
@Service
@RequiredArgsConstructor
public class DecisionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DecisionOrchestrator.class);

    // TODO parametrize — périmètre DCA actuel (roadmap Palier 3, étape 7), pas une liste figée
    // définitivement (exclut à tort un utilisateur qui veut démarrer un DCA sur un actif qu'il ne
    // possède pas encore si on la déduisait des soldes de wallet, cf. tour d'horizon du 2026-08-12).
    private static final List<String> TRACKED_ASSETS = List.of("BTC", "ETH", "PAXG");

    private final TreeAnalysisFacade treeAnalysisFacade;
    private final ScenarioEngine scenarioEngine;
    private final UserRepository userRepository;
    private final OwnerRefreshGuard refreshGuard;
    private final IndicatorCredentialResolver credentialResolver;
    private final TrendConfirmationStrategy trendConfirmationStrategy;
    private final MovementQualificationStrategy movementQualificationStrategy;
    private final OrderFlowStrategy orderFlowStrategy;
    private final EtfFlowConfidenceStrategy etfFlowConfidenceStrategy;
    private final DomainClock clock;

    public OrchestrationResult runCycle() {
        Instant now = clock.now();

        MarketOpinionParameters localParams = buildDefaultLocalOpinionParams();
        MarketOpinionParameters emptyParams = MarketOpinionParameters.builder().build();

        List<OpinionSignal> signals = new ArrayList<>();
        for (String asset : TRACKED_ASSETS) {
            signals.add(treeAnalysisFacade.getOpinion(asset, OpinionScope.LOCAL, localParams));
        }
        // GLOBAL/MACRO : une fois par cycle, symbole arbitraire (ignoré, cf. fix étape 1 — le
        // résultat ne porte jamais ce symbole depuis le fix GlobalMarketOpinion/MacroMarketOpinion).
        signals.add(treeAnalysisFacade.getOpinion(TRACKED_ASSETS.getFirst(), OpinionScope.GLOBAL, emptyParams));
        signals.add(treeAnalysisFacade.getOpinion(TRACKED_ASSETS.getFirst(), OpinionScope.MACRO, emptyParams));

        List<User> activeUsers = userRepository.findByEnabledTrueAndArchivedAtIsNull();

        int processed = 0;
        int skippedLocked = 0;
        for (User user : activeUsers) {
            ScenarioOwner owner = ScenarioOwner.of(user);
            if (!refreshGuard.tryAcquire(owner, now)) {
                skippedLocked++;
                log.debug("DecisionOrchestrator: owner {} verrouillé, cycle ignoré pour ce run.", owner);
                continue;
            }
            try {
                for (OpinionSignal signal : signals) {
                    ScenarioContext context = new ScenarioContext(owner, signal.symbol(), clock, List.of());
                    scenarioEngine.onMarketOpinion(signal, context);
                }
                processed++;
            } finally {
                refreshGuard.release(owner, clock.now());
            }
        }

        log.info("DecisionOrchestrator: cycle terminé — {} signal(aux) calculé(s), {} owner(s) traité(s), "
                        + "{} owner(s) ignoré(s) (verrou).",
                signals.size(), processed, skippedLocked);

        return new OrchestrationResult(signals.size(), activeUsers.size(), processed, skippedLocked, now);
    }

    /**
     * Combine TrendConfirmation + MovementQualification + OrderFlow + EtfFlow (modulateurs de
     * confiance) — décision 7 du prompt d'implémentation de cette étape. Concaténation des
     * StrategyKey des 4 fabriques, patron déjà documenté dans MarketOpinionParametersFactory.
     * Valeurs de seuils reprises telles quelles de {@code TrendConfirmationStrategy.DEFAULT_*}
     * (mêmes valeurs que {@code TrendConfirmationStrategyTest}) pour TrendConfirmation ; les 3
     * autres réutilisent leurs {@code Param.defaults(...)} respectifs.
     */
    private MarketOpinionParameters buildDefaultLocalOpinionParams() {
        List<StrategyKey> keys = new ArrayList<>();

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(
                trendConfirmationStrategy,
                new StrategyParametersFactory.TrendConfirmationParam(
                        TimeFrame.H1, 10, 20, 14, 14,
                        15.0, 25.0, 80.0, 20.0)
        ).getStrategies());

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithMovementQualification(
                movementQualificationStrategy,
                StrategyParametersFactory.MovementQualificationParam.defaults(TimeFrame.H1, 14.0),
                credentialResolver.resolve(IndicatorType.OPEN_INTEREST)
        ).getStrategies());

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithOrderFlow(
                orderFlowStrategy,
                StrategyParametersFactory.OrderFlowParam.defaults(TimeFrame.H1),
                credentialResolver.resolve(IndicatorType.LIQUIDATIONS)
        ).getStrategies());

        keys.addAll(MarketOpinionParametersFactory.buildLocalOpinionParamWithEtfFlow(
                etfFlowConfidenceStrategy,
                StrategyParametersFactory.EtfFlowConfidenceParam.defaults(),
                credentialResolver.resolve(IndicatorType.ETF_FLOW)
        ).getStrategies());

        return MarketOpinionParameters.builder().strategies(keys).build();
    }
}
