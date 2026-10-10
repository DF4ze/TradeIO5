package fr.ses10doigts.tradeIO5.service.execution.exchange;

import java.math.BigDecimal;

/** État d'un ordre : quantité cumulée remplie (base) et prix moyen ({@code avgPx} nul tant que rien n'est rempli). */
public record OrderState(String clOrdId, String ordId, OrderPhase phase, BigDecimal accFillSz, BigDecimal avgPx) {

    public boolean terminal() {
        return phase.terminal();
    }
}
