package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.math.BigDecimal;

/** Paire du catalogue d'un exchange (liste du testeur d'ordre). {@code costMin} nul si l'exchange n'en porte pas. */
public record AdminInstrumentDto(String instId, String state, BigDecimal minSz, BigDecimal costMin, BigDecimal lotSz,
                                 BigDecimal tickSz, String feeCurrency) {
}
