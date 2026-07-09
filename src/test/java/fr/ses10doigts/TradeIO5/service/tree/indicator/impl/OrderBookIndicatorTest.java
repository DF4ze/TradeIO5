package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator - OrderBookIndicator.computeVolumes")
class OrderBookIndicatorTest {

    private static OrderBookSnapshot.OrderBookLevel level(String price, String qty) {
        return new OrderBookSnapshot.OrderBookLevel(new BigDecimal(price), new BigDecimal(qty));
    }

    @Test
    @DisplayName("carnet équilibré => imbalance proche de 0")
    void computeVolumes_balancedBook_producesNearZeroImbalance() {
        OrderBookSnapshot snapshot = new OrderBookSnapshot(
                List.of(level("100.00", "10"), level("99.90", "10")),
                List.of(level("100.10", "10"), level("100.20", "10"))
        );

        Map<String, Double> result = OrderBookIndicator.computeVolumes(snapshot, 1.0);

        assertEquals(20.0, result.get(OrderBookIndicator.V_BID_VOLUME), 0.001);
        assertEquals(20.0, result.get(OrderBookIndicator.V_ASK_VOLUME), 0.001);
        assertEquals(0.0, result.get(OrderBookIndicator.V_IMBALANCE), 0.001);
    }

    @Test
    @DisplayName("carnet déséquilibré côté acheteur => imbalance positif")
    void computeVolumes_bidHeavyBook_producesPositiveImbalance() {
        OrderBookSnapshot snapshot = new OrderBookSnapshot(
                List.of(level("100.00", "90"), level("99.90", "10")),
                List.of(level("100.10", "5"), level("100.20", "5"))
        );

        Map<String, Double> result = OrderBookIndicator.computeVolumes(snapshot, 1.0);

        assertEquals(100.0, result.get(OrderBookIndicator.V_BID_VOLUME), 0.001);
        assertEquals(10.0, result.get(OrderBookIndicator.V_ASK_VOLUME), 0.001);
        assertTrue(result.get(OrderBookIndicator.V_IMBALANCE) > 0.5,
                "un carnet largement dominé par les bids doit donner une imbalance nettement positive");
    }

    @Test
    @DisplayName("carnet vide/absent => null (traité comme invalid par l'appelant)")
    void computeVolumes_returnsNull_whenBookEmpty() {
        assertNull(OrderBookIndicator.computeVolumes(null, 1.0));
        assertNull(OrderBookIndicator.computeVolumes(new OrderBookSnapshot(List.of(), List.of()), 1.0));
    }
}
