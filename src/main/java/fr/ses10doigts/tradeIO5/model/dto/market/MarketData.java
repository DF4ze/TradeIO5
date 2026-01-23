package fr.ses10doigts.tradeIO5.model.dto.market;

import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Builder
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
}
