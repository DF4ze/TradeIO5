package fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Résultat "propre" du provider DefiLlama (étude "indicateurs-macro-externes", §7) : capitalisation
 * totale des stablecoins peggés USD, plus les 3 points d'évolution déjà fournis par l'API
 * ({@code circulatingPrevDay}/{@code circulatingPrevWeek}/{@code circulatingPrevMonth}), pour
 * garder la même lecture "niveau + évolution" que {@link FearAndGreedResponse}.
 * <p>
 * Distinct du DTO de désérialisation brute de la réponse DefiLlama (qui porte la liste complète
 * {@code peggedAssets}) : celui-ci est déjà agrégé (somme des {@code peggedUSD} sur tous les
 * éléments dont {@code pegType == "peggedUSD"}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StablecoinMarketCapResponse {

    private double total;
    private double totalPrevDay;
    private double totalPrevWeek;
    private double totalPrevMonth;

    private boolean valid = true;

    public static StablecoinMarketCapResponse invalid() {
        StablecoinMarketCapResponse response = new StablecoinMarketCapResponse();
        response.setValid(false);
        return response;
    }
}
