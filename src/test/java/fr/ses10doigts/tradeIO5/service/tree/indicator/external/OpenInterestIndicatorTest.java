package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.OpenInterestHistoryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Indicator External - OpenInterestIndicator")
class OpenInterestIndicatorTest {

    @Test
    @DisplayName("extractCurrentPrevious() prend les 2 derniers points, triés par timestamp")
    void extractCurrentPrevious_takesLastTwoPointsSortedByTimestamp() {
        // Volontairement dans le désordre pour vérifier le tri par 't'.
        OpenInterestHistoryResponse.HistoryPoint p3 = point(3000, 130.0);
        OpenInterestHistoryResponse.HistoryPoint p1 = point(1000, 100.0);
        OpenInterestHistoryResponse.HistoryPoint p2 = point(2000, 115.0);

        Map<String, Double> result = OpenInterestIndicator.extractCurrentPrevious(List.of(p3, p1, p2));

        assertEquals(130.0, result.get(OpenInterestIndicator.V_CURRENT), 0.001);
        assertEquals(115.0, result.get(OpenInterestIndicator.V_PREVIOUS), 0.001);
    }

    @Test
    @DisplayName("extractCurrentPrevious() retourne null si moins de 2 points sont disponibles")
    void extractCurrentPrevious_returnsNull_whenFewerThanTwoPoints() {
        assertNull(OpenInterestIndicator.extractCurrentPrevious(List.of(point(1000, 100.0))));
        assertNull(OpenInterestIndicator.extractCurrentPrevious(List.of()));
        assertNull(OpenInterestIndicator.extractCurrentPrevious(null));
    }

    private static OpenInterestHistoryResponse.HistoryPoint point(long t, double close) {
        OpenInterestHistoryResponse.HistoryPoint p = new OpenInterestHistoryResponse.HistoryPoint();
        p.setT(t);
        p.setC(close);
        return p;
    }
}
