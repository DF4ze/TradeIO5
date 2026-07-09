package fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

/**
 * Résultat "propre" du scraping Farside (étude "indicateurs-macro-externes", §14, item I) :
 * flux ETF du dernier jour avec donnée publiée, par émetteur + total. Même patron "niveau agrégé
 * déjà nettoyé" que {@link StablecoinMarketCapResponse} : distinct du HTML brut, construit par
 * {@code FarsideEtfFlowClient} après parsing.
 * <p>
 * {@code date}/{@code byIssuer}/{@code total} correspondent à la dernière ligne du tableau dont
 * au moins une valeur par émetteur est publiée (donc en excluant une ligne "aujourd'hui" où toutes
 * les cases valent "-" avant la clôture des marchés US).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EtfFlowResponse {

    private LocalDate date;

    /** Ticker (ex. "IBIT") -> flux du jour en millions USD. N'inclut que les émetteurs dont la
     *  case n'était pas "-" ce jour-là (cf. règle de parsing "-" != absence != 0.0). */
    private Map<String, Double> byIssuer;

    /** Flux total du jour (colonne "Total" de la ligne, pas la ligne de synthèse "Total" du bas
     *  de tableau qui est un cumul depuis le lancement). */
    private Double total;

    private boolean valid = true;

    public static EtfFlowResponse invalid() {
        EtfFlowResponse response = new EtfFlowResponse();
        response.setValid(false);
        return response;
    }
}
