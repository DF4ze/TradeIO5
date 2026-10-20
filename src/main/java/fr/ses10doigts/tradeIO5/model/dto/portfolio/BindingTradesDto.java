package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Achats/ventes réels d'un lien (wallet x actif) et prix moyen d'achat (nul sans achat). */
public record BindingTradesDto(Long bindingId, String assetSymbol, Double avgBuyPrice, List<Trade> trades) {

    public record Trade(Long id, LocalDateTime timestamp, String side, BigDecimal quantity, BigDecimal price, BigDecimal fee,
                        String feeCurrency) {
    }
}
