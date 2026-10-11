package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot.OrderBookLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BookSlippage : écart VWAP / meilleur prix au montant")
class BookSlippageTest {

    private static OrderBookLevel level(String price, String qty) {
        return new OrderBookLevel(new BigDecimal(price), new BigDecimal(qty));
    }

    private static OrderBookSnapshot book(List<OrderBookLevel> bids, List<OrderBookLevel> asks) {
        return new OrderBookSnapshot(bids, asks);
    }

    @Test
    @DisplayName("BUY dans le 1er niveau : slippage 0")
    void buyTopOfBook() {
        var book = book(List.of(level("99", "5")), List.of(level("100", "10")));
        assertEquals(0, BigDecimal.ZERO.compareTo(BookSlippage.slippagePct(book, LegSide.BUY, new BigDecimal("500")).orElseThrow()));
    }

    @Test
    @DisplayName("BUY sur 2 niveaux : VWAP 101,0097 vs 100 => ≈1,0097 %")
    void buyTwoLevels() {
        var book = book(List.of(level("99", "5")), List.of(level("100", "5"), level("102", "10")));
        BigDecimal slippage = BookSlippage.slippagePct(book, LegSide.BUY, new BigDecimal("1020")).orElseThrow();
        assertTrue(slippage.subtract(new BigDecimal("1.0097")).abs().compareTo(new BigDecimal("0.0005")) < 0, "slippage " + slippage);
    }

    @Test
    @DisplayName("SELL sur 2 niveaux : 10 à vendre, 5 à 100 + 5 à 99 => VWAP 99,5 => 0,5 %")
    void sellTwoLevels() {
        var book = book(List.of(level("100", "5"), level("99", "10")), List.of(level("101", "5")));
        BigDecimal slippage = BookSlippage.slippagePct(book, LegSide.SELL, new BigDecimal("1000")).orElseThrow();
        assertEquals(0, new BigDecimal("0.5").compareTo(slippage), "slippage " + slippage);
    }

    @Test
    @DisplayName("Carnet trop peu profond ou vide => vide")
    void insufficientDepth() {
        var shallow = book(List.of(level("99", "1")), List.of(level("100", "1")));
        assertTrue(BookSlippage.slippagePct(shallow, LegSide.BUY, new BigDecimal("1000")).isEmpty());
        assertTrue(BookSlippage.slippagePct(shallow, LegSide.SELL, new BigDecimal("1000")).isEmpty());
        var empty = book(List.of(), List.of());
        assertTrue(BookSlippage.slippagePct(empty, LegSide.BUY, BigDecimal.TEN).isEmpty());
        assertTrue(BookSlippage.slippagePct(empty, LegSide.SELL, BigDecimal.TEN).isEmpty());
    }
}
