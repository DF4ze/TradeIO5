package fr.ses10doigts.tradeIO5.model.dto.execution;

import java.math.BigDecimal;

/** Paire spot négociable d'un exchange (vue du catalogue pour le calcul de chemin). Tailles en unités de la base. */
public record InstrumentInfo(String instId, String base, String quote, BigDecimal minSz, BigDecimal lotSz,
                             BigDecimal tickSz) {
}
