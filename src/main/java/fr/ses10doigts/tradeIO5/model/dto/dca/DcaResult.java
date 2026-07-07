package fr.ses10doigts.tradeIO5.model.dto.dca;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Résultat complet d'une simulation DCA (cf. docs/etude-dca-tool-mcp.md section 5 pour les
 * formules et section 6 pour le patron d'implémentation).
 * <p>
 * {@code avgPrice} est une moyenne pondérée par les montants investis
 * ({@code totalInvested / totalQuantity}), pas une moyenne simple des prix — un DCA achète plus
 * d'unités quand le prix est bas, la moyenne simple surestimerait le coût réel.
 */
@Builder
@Data
public class DcaResult {

    private final String symbol;
    private final MarketDataSource source;
    private final TimeFrame frequency;
    private final int purchaseHourUtc;
    private final Instant firstOccurrence;
    private final Instant lastOccurrence;

    /** Nombre total d'échéances du calendrier (résolues + manquantes). */
    private final int occurrenceCount;

    /** Nombre d'échéances sans bougie trouvée (cf. {@link DcaOccurrence#isMissing()}). */
    private final int missingCount;

    private final BigDecimal totalInvested;
    private final BigDecimal totalFees;
    private final BigDecimal totalQuantity;
    private final BigDecimal avgPrice;

    private final BigDecimal currentPrice;
    private final BigDecimal currentValue;
    private final BigDecimal pnl;
    private final BigDecimal pnlPercent;

    /** Détail de chaque échéance, résolue ou manquante. */
    private final List<DcaOccurrence> occurrences;
}
