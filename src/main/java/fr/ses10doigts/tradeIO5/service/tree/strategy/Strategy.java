package fr.ses10doigts.tradeIO5.service.tree.strategy;


import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;

import java.util.Map;
import java.util.Set;

/**
 * - Pourquoi
 * Une stratégie est une opinion, pas une action.
 *
 * - Responsabilité
 * lire le snapshot
 * exprimer une confidence graduée
 * pouvoir être ignorée
 *
 * - Elle ne fait jamais :
 * d’ordre
 * d’accès exchange
 * de logique temporelle globale
 *
 * - Conceptuellement
 * “Dans ce contexte, je penche plutôt pour…”
 */
public interface Strategy {

    Map<TimeFrame, Integer> getRequiredCandles(StrategyParameters parameters );

    StrategySignal evaluate(MarketContext context, StrategyParameters parameters);

    Set<StrategyType> getType();

    /**
     * Permet à {@link StrategyRegistry#resolveBestMatch(StrategyType, StrategyParameters)} de
     * discriminer entre plusieurs Strategies qui partagent le même {@link StrategyType} (ex :
     * DoubleRsiStrategy et TrendConfirmationStrategy déclarent toutes deux ENTRY), en vérifiant
     * que les {@code IndicatorParameters} fournis correspondent à ce que cette Strategy attend
     * réellement (nombre et {@code IndicatorType} des indicateurs), plutôt que de résoudre
     * uniquement par {@code StrategyType} et prendre la première trouvée au hasard.
     * <p>
     * Par défaut, {@code true} (comportement historique inchangé pour toute Strategy qui ne
     * surcharge pas cette méthode) : la désambiguïsation ne s'applique qu'aux Strategies qui la
     * déclarent explicitement.
     */
    default boolean accepts(StrategyParameters parameters) {
        return true;
    }

    default String getName() {
        return this.getClass().getSimpleName();
    }
}
