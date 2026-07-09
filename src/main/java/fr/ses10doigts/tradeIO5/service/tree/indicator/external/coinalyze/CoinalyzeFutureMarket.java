package fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Désérialisation de {@code GET https://api.coinalyze.net/v1/future-markets} — DTO interne au
 * package. {@code symbol} est le code Coinalyze exact à utiliser dans les appels
 * OI/funding/liquidations (ex. {@code "BTCUSDT_PERP.A"}) ; {@code symbolOnExchange} est le
 * symbole natif de l'exchange (ex. {@code "BTCUSDT"} sur Binance) sur lequel on filtre
 * (voir {@link CoinalyzeSymbolResolver}). Le suffixe après le point dans {@code symbol} encode
 * l'exchange et n'est jamais codé en dur ici — voir étape 0 de l'étude "indicateurs-macro-externes"
 * §6b/§9/§12.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoinalyzeFutureMarket {

    private String symbol;
    private String exchange;

    @JsonProperty("symbol_on_exchange")
    private String symbolOnExchange;

    @JsonProperty("base_asset")
    private String baseAsset;

    @JsonProperty("quote_asset")
    private String quoteAsset;

    @JsonProperty("is_perpetual")
    private Boolean isPerpetual;
}
