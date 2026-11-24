package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("LiveSlot.SERVICE_ORDER : priorité puis ancienneté du lien, plus de rang BTC / ETH / PAXG")
class LiveSlotOrderTest {

    private static LiveSlot slot(long id, String asset, int priority) {
        return new LiveSlot(id, 1L, asset, 1L, priority, 100, null);
    }

    @Test
    @DisplayName("À priorité égale, le lien le plus ancien (id) passe en premier, quel que soit l'actif")
    void seniorityBreaksTies() {
        List<LiveSlot> sorted = List.of(slot(30, "BTC", 0), slot(10, "SOL", 0), slot(20, "ETH", 0), slot(5, "PAXG", 1)).stream()
                .sorted(LiveSlot.SERVICE_ORDER).toList();
        assertEquals(List.of(10L, 20L, 30L, 5L), sorted.stream().map(LiveSlot::bindingId).toList());
    }
}
