package fr.ses10doigts.tradeIO5.model.dto.execution;

import java.math.BigDecimal;

/** Paire spot négociable d'un exchange (vue du catalogue pour le calcul de chemin). Tailles en unités de la base ; {@code costMin} (nullable) en cotation. */
public record InstrumentInfo(String instId, String base, String quote, BigDecimal minSz, BigDecimal lotSz,
                             BigDecimal tickSz, BigDecimal costMin, String feeCurrency) {

    public InstrumentInfo(String instId, String base, String quote, BigDecimal minSz, BigDecimal lotSz, BigDecimal tickSz) {
        this(instId, base, quote, minSz, lotSz, tickSz, null, null);
    }

    /** Coût minimum de l'exchange en cotation (nul pour OKX) : {@code coût = taille × prix}. */
    public boolean belowCostMin(BigDecimal cost) {
        return costMin != null && cost.compareTo(costMin) < 0;
    }
}
