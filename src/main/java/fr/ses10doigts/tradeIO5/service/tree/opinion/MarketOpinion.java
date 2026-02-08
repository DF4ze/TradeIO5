package fr.ses10doigts.tradeIO5.service.tree.opinion;

import com.github.f4b6a3.ulid.UlidCreator;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;

import java.util.Map;



/**
 * Contrat commun à toutes les opinions.
 * Une opinion orchestre une ou plusieurs Strategies
 * et produit un OpinionResult à partir d'un contexte enrichi.
 */
public interface MarketOpinion {

    default String getId(){
        return "["+getName()+"]"+UlidCreator.getUlid().toString();
    }

    /**
     * Type de l'opinion (pour le registry)
     * Contrat moral de l'opinion : « Elle se cantonne à ce périmètre.
     */
    OpinionScope getScope();

    /**
     * Nombre de bougies nécessaire pour prendre avoir cette opinion
     */
    Map<TimeFrame, Integer> getRequiredCandles(MarketOpinionParameters parameters );

    /**
     * Point d'entrée principal de l'opinion'.
     *
     * @param context    contexte enrichi
     * @param parameters paramètres génériques de l'opinion'
     *
     * Must emit an event.
     */
    void decide(OpinionContext context, MarketOpinionParameters parameters);

    /**
     * Retourne le nom de la classe pour le Registry
     * @return
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
