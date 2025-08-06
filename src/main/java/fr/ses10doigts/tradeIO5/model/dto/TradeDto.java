package fr.ses10doigts.tradeIO5.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeDto {
    private String tradeId;         // Identifiant unique du trade (externe)
    private String asset;           // Symbole de l'asset (ex : BTC)
    private BigDecimal quantity;    // Quantité achetée/vendue
    private BigDecimal price;       // Prix unitaire d'exécution
    private LocalDateTime timestamp;// Date et heure du trade
    private TradeSide side;         // Côté trade : BUY ou SELL
    private BigDecimal fee;         // Frais du trade (optionnel)
}