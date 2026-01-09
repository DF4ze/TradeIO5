package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator;


import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorValue;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;


/**
 * DB
 *  └─ IndicatorParameterSet
 *       └─ IndicatorParameter
 *               ↓
 *         (mapping service)
 *               ↓
 *         IndicatorParameters   ← runtime
 *
 * Market data
 *       ↓
 * IndicatorContext
 *       ↓
 * Indicator.compute(...)
 *       ↓
 * IndicatorValue
 *       ↓
 * IndicatorSnapshot
 *       ↓
 * StrategyEngine
 */


/**
 * - Pourquoi
 * Un indicateur ne doit jamais décider. Il décrit l’état du monde.
 *
 * - Responsabilité
 * produire une mesure
 * être déterministe
 * ne rien connaître du DCA, du user, ni de l’argent
 *
 * - Conceptuellement
 * “Voici ce que j’observe, maintenant.”
 */
public interface Indicator {
    /**
     * Code fonctionnel stable (RSI, EMA, MACD, etc.)
     */
    IndicatorCode getCode();

    /**
     * Exécution pure de l'indicateur
     */
    IndicatorValue compute(
            IndicatorContext input,
            IndicatorParameters parameters
    );
}
