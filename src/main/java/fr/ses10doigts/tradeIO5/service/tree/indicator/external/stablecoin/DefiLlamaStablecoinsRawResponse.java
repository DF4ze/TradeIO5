package fr.ses10doigts.tradeIO5.service.tree.indicator.external.stablecoin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.StablecoinMarketCapResponse;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Désérialisation brute de {@code GET https://stablecoins.llama.fi/stablecoins?includePrices=true}
 * — DTO interne au client, pas exposé au reste du projet (voir {@link StablecoinMarketCapResponse}
 * pour le DTO "propre" déjà agrégé). Ne porte que les champs utilisés par l'agrégation (§7 de
 * l'étude "indicateurs-macro-externes") ; {@code @JsonIgnoreProperties(ignoreUnknown = true)} pour
 * tolérer les nombreux autres champs renvoyés par l'API (chains, price, etc.) sans les mapper.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DefiLlamaStablecoinsRawResponse {

    private List<PeggedAsset> peggedAssets;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PeggedAsset {
        private String id;
        private String name;
        private String symbol;
        private String pegType;
        private Map<String, Double> circulating;
        private Map<String, Double> circulatingPrevDay;
        private Map<String, Double> circulatingPrevWeek;
        private Map<String, Double> circulatingPrevMonth;
    }
}
