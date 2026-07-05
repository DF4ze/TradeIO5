package fr.ses10doigts.tradeIO5.model.dto.market;

import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

// @EqualsAndHashCode structurel (pas seulement @Getter) : nécessaire pour que
// MarketDataset (et donc IndicatorContext/IndicatorExecutionKey) puisse comparer par
// contenu plutôt que par identité d'objet. Sans ça, IndicatorCache ne fait jamais de hit
// entre deux appels séparés, même sur des données identiques (cf. MarketDataset).
@Getter
@Builder
@EqualsAndHashCode
public class MarketData {
    /** TimeFrame de la bougie, 1 mois, 4 heures... */
    private final TimeFrame timeFrame;

    /** Timestamps des bougies */
    private final Instant timestamp;

    /** Asset pair */
    private final String pair;

    /** Prix d’ouverture */
    private final BigDecimal open;

    /** Plus hauts */
    private final BigDecimal high;

    /** Plus bas */
    private final BigDecimal low;

    /** Prix de clôture */
    private final BigDecimal close;

    /** Volumes échangés */
    private final BigDecimal volume;

    @Override
    public String toString() {
        return "MarketData{" +
                "timestamp=" + timestamp +
                ", close=" + close +
                '}';
    }
}
