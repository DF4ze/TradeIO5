package fr.ses10doigts.tradeIO5.model.dto.portfolio;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Achat/vente réel d'un wallet (tous wallets de l'utilisateur ou un seul), plus récent d'abord ; {@code notional} = quantité x prix, dans {@code quoteCurrency} (nulle = dollar).
 * {@code origin} : {@code MANUAL} (passé à la main sur l'exchange) ou {@code TRADEIO} (ordre envoyé par TradeIO). */
public record TradeLineDto(Long id, LocalDateTime timestamp, String side, String assetSymbol, BigDecimal quantity,
                           BigDecimal price, BigDecimal notional, BigDecimal fee, String feeCurrency, Long walletId,
                           String walletName, String exchange, String quoteCurrency, String origin) {
}
