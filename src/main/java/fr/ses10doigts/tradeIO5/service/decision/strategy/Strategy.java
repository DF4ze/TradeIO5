package fr.ses10doigts.tradeIO5.service.decision.strategy;


import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.StrategyType;

import java.util.Set;

/**
 * - Pourquoi
 * Une stratégie est une opinion, pas une action.
 *
 * - Responsabilité
 * lire le snapshot
 * exprimer une conviction graduée
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
    
    StrategySignal evaluate(MarketContext context, StrategyParameters parameters);

    Set<StrategyType> getType();

    default String getName() {
        return this.getClass().getSimpleName();
    }
}
