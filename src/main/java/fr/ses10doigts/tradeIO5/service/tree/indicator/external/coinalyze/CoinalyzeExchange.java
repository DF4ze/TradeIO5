package fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Désérialisation de {@code GET https://api.coinalyze.net/v1/exchanges} — DTO interne au
 * package, utilisé uniquement pour retrouver le {@code code} exchange (ex. Binance) attendu par
 * {@link CoinalyzeSymbolResolver}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoinalyzeExchange {
    private String code;
    private String name;
}
