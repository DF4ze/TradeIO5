package fr.ses10doigts.tradeIO5.service.tree.indicator;


import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;

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
     * {@code true} si l'indicateur ne dépend jamais du symbole interrogé (valeur macro/marché
     * globale, ex: DXY, SP500, NASDAQ, FEAR_GREED, STABLECOIN_MARKET_CAP, ETF_FLOW qui lit
     * uniquement son paramètre {@code asset}) — {@code false} par défaut (comportement historique,
     * ex: RSI/EMA ou OPEN_INTEREST/FUNDING_RATE/LIQUIDATIONS qui varient bien par actif).
     * <p>
     * Utilisé par {@code IndicatorExecutionKey.of(...)} pour neutraliser {@code symbol}/
     * {@code marketDataset} dans la clé de cache de ces indicateurs : sans ça, deux appels sur des
     * symboles différents (ex: {@code get_opinion("BTC", MACRO, ...)} puis
     * {@code get_opinion("ETH", MACRO, ...)}) déclenchaient chacun un appel réseau distinct pour
     * une même donnée globale — incident 2026-08-17, DXY consommait 2×6=12 crédits Twelve Data/
     * minute (limite gratuite : 8) alors qu'une seule évaluation en coûte 6.
     */
    default boolean isGlobal() {
        return false;
    }

    int getRequiredData( IndicatorParameters parameters );

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
            } else if (("wallet".equals(key) || "credential".equals(key)) && parameters.getCredential() != null ) {
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
