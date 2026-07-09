package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.LiquidationHistoryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Indicator External - LiquidationsIndicator.sumHistory")
class LiquidationsIndicatorTest {

    @Test
    @DisplayName("somme correctement plusieurs points d'historique sur la fenêtre demandée")
    void sumHistory_sumsMultipleHistoryPoints() {
        LiquidationHistoryResponse.HistoryPoint p1 = new LiquidationHistoryResponse.HistoryPoint();
        p1.setT(1720000000);
        p1.setL(10.0);
        p1.setS(3.0);

        LiquidationHistoryResponse.HistoryPoint p2 = new LiquidationHistoryResponse.HistoryPoint();
        p2.setT(1720003600);
        p2.setL(1.5);
        p2.setS(0.5);

        LiquidationHistoryResponse.HistoryPoint p3 = new LiquidationHistoryResponse.HistoryPoint();
        p3.setT(1720007200);
        p3.setL(0.0);
        p3.setS(2.0);

        Map<String, Double> totals = LiquidationsIndicator.sumHistory(List.of(p1, p2, p3));

        assertEquals(11.5, totals.get(LiquidationsIndicator.V_LONG), 0.001);
        assertEquals(5.5, totals.get(LiquidationsIndicator.V_SHORT), 0.001);
        assertEquals(17.0, totals.get(LiquidationsIndicator.V_TOTAL), 0.001);
    }

    @Test
    @DisplayName("retourne null si l'historique est absent")
    void sumHistory_returnsNull_whenHistoryMissing() {
        assertNull(LiquidationsIndicator.sumHistory(null));
    }
}
