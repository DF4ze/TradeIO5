package fr.ses10doigts.tradeIO5.model.dto.dca;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Une échéance d'achat du calendrier DCA (cf. docs/etude-dca-tool-mcp.md section 3) et son
 * résultat : soit le prix a été résolu (bougie H1 trouvée à cet instant) et {@code missing} est
 * {@code false}, soit aucune bougie n'a été trouvée ({@code missing = true}) et {@code price}/
 * {@code quantity}/{@code fee} restent {@code null} — cette échéance est alors exclue des sommes
 * {@code totalInvested}/{@code totalQuantity} de {@link DcaResult}, mais son montant prévu reste
 * visible via {@code plannedAmount} pour ne pas fausser silencieusement le calcul (cf. section 7
 * de l'étude : "Occurrence sans prix trouvé").
 */
@Builder
@Data
public class DcaOccurrence {

    /** Instant de l'échéance (UTC), avant alignement sur la grille H1. */
    private final Instant timestamp;

    /** Montant qui aurait dû être investi à cette échéance (montant fixe demandé). */
    private final BigDecimal plannedAmount;

    /** {@code true} si aucune bougie H1 n'a été trouvée pour cette échéance. */
    private final boolean missing;

    /** Prix d'ouverture de la bougie H1 couvrant l'échéance ({@code null} si {@code missing}). */
    private final BigDecimal price;

    /** Frais prélevés sur cette échéance ({@code plannedAmount * feePercent / 100}, {@code null} si {@code missing}). */
    private final BigDecimal fee;

    /** Quantité achetée : {@code (plannedAmount - fee) / price} ({@code null} si {@code missing}). */
    private final BigDecimal quantity;
}
