package fr.ses10doigts.tradeIO5.service.tree.indicator;


import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;

import java.util.List;


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
    IndicatorType getType();

    /**
     * Exécution pure de l'indicateur
     */
    IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    );

    /**
     * Récupération de la liste des paramètres
     */
    List<String> getParametersNames();

    /**
     * Vérifie la validité des paramètres
     */
    default boolean checkParameters(IndicatorParameters parameters) {
        boolean forAll = true;
        for (String key : getParametersNames()) {
            boolean found = false;
            if (parameters.getNumerics() != null && parameters.getNumeric(key) != null) {
                found = true;
            }else if( parameters.getBooleans() != null && parameters.getBoolean(key) != null){
                found = true;
            }else if (parameters.getStrings() != null && parameters.getString(key) != null){
                found = true;
            } else if ("wallet".equals(key) && parameters.getCredential() != null ) {
                found = true;
            }

            if( !found ) {
                forAll = false;
                break;
            }
        }
        return forAll;
    }
}
