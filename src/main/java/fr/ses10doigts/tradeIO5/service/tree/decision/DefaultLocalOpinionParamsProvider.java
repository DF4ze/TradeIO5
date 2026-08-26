package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.EtfFlowConfidenceStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Combine TrendConfirmation + MovementQualification + OrderFlow + EtfFlow (modulateurs de confiance)
 * en un unique {@link MarketOpinionParameters} — les paramètres LOCAL "par défaut" utilisés pour un
 * calcul d'Opinion automatique/manuel non personnalisé (décision 7 du prompt d'implémentation de
 * l'étape 7 du Palier 3). Concaténation des {@code StrategyKey} des 4 fabriques, patron déjà
 * documenté dans {@link MarketOpinionParametersFactory}. Valeurs de seuils reprises telles quelles de
 * {@code TrendConfirmationStrategy.DEFAULT_*} (mêmes valeurs que {@code TrendConfirmationStrategyTest})
 * pour TrendConfirmation ; les 3 autres réutilisent leurs {@code Param.defaults(...)} respectifs.
 * <p>
 * Extrait de {@link DecisionOrchestrator} (2026-08-17, plan de test manuel Palier 3) dans un
 * composant partagé : {@code OpinionAdminController} (déclenchement manuel d'une Opinion, hors cycle
 * automatique) l'utilise désormais aussi, pour garantir que les deux chemins calculent toujours la
 * même chose — une divergence entre les deux aurait rendu le déclenchement manuel trompeur pour
 * vérifier le comportement réel du cycle automatique.
 */
@Component
@RequiredArgsConstructor
public class DefaultLocalOpinionParamsProvider {

    private final IndicatorCredentialResolver credentialResolver;
    private final TrendConfirmationStrategy trendConfirmationStrategy;
    private final MovementQualificationStrategy movementQualificationStrategy;
    private final OrderFlowStrategy orderFlowStrategy;
    private final EtfFlowConfidenceStrategy etfFlowConfidenceStrategy;

    public MarketOpinionParameters build() {
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
