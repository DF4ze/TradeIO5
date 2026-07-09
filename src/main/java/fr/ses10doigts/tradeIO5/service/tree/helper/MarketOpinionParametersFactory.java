package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;

import java.util.List;

public class MarketOpinionParametersFactory {

    /**
     * Branche {@link fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy}
     * (EMA + ADX + RSI) sur une {@code MarketOpinion} de scope {@code LOCAL}.
     */
    public static MarketOpinionParameters buildLocalOpinionParamWithTrendConfirmation(
            Strategy strategy,
            StrategyParametersFactory.TrendConfirmationParam param
    ){
        StrategyParameters strategyParameters = StrategyParametersFactory.buildTrendConfirmationStrategyParam(param);

        StrategyKey key = new StrategyKey(strategy, strategyParameters);

        return MarketOpinionParameters.builder()
                .strategies(List.of(key))
                .build();
    }

    /**
     * Branche {@link fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy}
     * (OI + Funding + OBV) sur une {@code MarketOpinion} de scope {@code LOCAL} — jusqu'ici (2026-07-09)
     * cette Strategy n'était utilisable qu'en construisant les {@code StrategyParameters} à la main
     * via {@code evaluate_strategy} (MCP), sans fabrique réutilisable équivalente à
     * {@link #buildLocalOpinionParamWithTrendConfirmation}. Limite déjà documentée dans
     * {@code MovementQualificationStrategy} : elle est agrégée comme une Strategy {@code ENTRY}
     * classique alors qu'elle joue plutôt un rôle de modulateur de confiance — ce branchement ne
     * change pas cette limite (mécanisme de modulation dédié toujours pas écrit), il rend
     * simplement la Strategy invocable depuis Java au même titre que TrendConfirmation. Pour
     * combiner les deux dans une seule Opinion, l'appelant peut concaténer les
     * {@code StrategyKey} des deux {@code MarketOpinionParameters} (voir {@code strategies()}).
     * <p>
     * {@code coinalyzeCredential} doit être résolue par l'appelant (ex:
     * {@code IndicatorCredentialResolver.resolve(IndicatorType.OPEN_INTEREST)}).
     */
    public static MarketOpinionParameters buildLocalOpinionParamWithMovementQualification(
            Strategy strategy,
            StrategyParametersFactory.MovementQualificationParam param,
            ApiCredentialDTO coinalyzeCredential
    ){
        StrategyParameters strategyParameters =
                StrategyParametersFactory.buildMovementQualificationStrategyParam(param, coinalyzeCredential);

        StrategyKey key = new StrategyKey(strategy, strategyParameters);

        return MarketOpinionParameters.builder()
                .strategies(List.of(key))
                .build();
    }
}
