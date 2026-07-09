package fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;

import java.util.List;
import java.util.Map;

/**
 * Point d'entrée unique Twelve Data pour les items E (DXY) et F (SP500/NASDAQ) de l'étude
 * "indicateurs-macro-externes" §14 — un seul provider, deux besoins de fraîcheur différents (voir
 * javadoc de {@link TwelveDataQuoteClient}) d'où les deux méthodes distinctes plutôt qu'une seule
 * générique.
 */
public interface TwelveDataQuoteProvider {

    /**
     * {@code GET /price} — prix "nu", sans timestamp. Utilisé pour les 6 paires forex de la
     * formule DXY (item E), qui tradent en continu (24/5) : la fraîcheur n'y est pas un problème
     * pratique au même degré que pour un indice actions.
     *
     * @return map symbole -&gt; prix pour les symboles demandés, potentiellement partielle si
     * certains symboles échouent côté API (entrée absente plutôt que valeur par défaut) ; map vide
     * (jamais {@code null}) si la requête échoue entièrement (timeout/4xx/5xx) — jamais
     * d'exception.
     */
    Map<String, Double> fetchPrices(ApiCredentialDTO credential, List<String> symbols);

    /**
     * {@code GET /quote} — prix + timestamp de dernière transaction. Utilisé pour les indices
     * actions de l'item F (SP500/NASDAQ), qui ne tradent pas 24/7 : voir
     * {@link TwelveDataQuote}.
     *
     * @return map symbole -&gt; quote, mêmes garde-fous que {@link #fetchPrices}.
     */
    Map<String, TwelveDataQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols);
}
