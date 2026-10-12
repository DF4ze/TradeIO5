package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PortfolioReading : statut, fraîcheur, relecture d'un snapshot")
class PortfolioReadingTest {

    private static final Instant T0 = Instant.parse("2026-10-01T23:55:00Z");

    @Test
    @DisplayName("Lecture indisponible : aucun solde, jamais OK")
    void unavailable() {
        PortfolioReading r = PortfolioReading.unavailable(7L, T0);

        assertFalse(r.isOk());
        assertEquals(0.0, r.cash(), 0);
        assertEquals(0.0, r.quantity("BTC"), 0);
        assertEquals(7L, r.walletId());
    }

    @Test
    @DisplayName("Relecture d'un snapshot : cash du pool, position de l'actif, wallet, instant, statut")
    void fromSnapshot() {
        RainbowLiveLiveBlock block = RainbowLiveLiveBlock.builder().status(PortfolioStatus.OK).walletId(7L)
                .fetchedAt(T0).cashUsd(120.0).positionQty(0.5).build();

        PortfolioReading r = PortfolioReading.fromSnapshot(block, "BTC");

        assertTrue(r.isOk());
        assertEquals(120.0, r.cash(), 0);
        assertEquals(0.5, r.quantity("BTC"), 0);
        assertEquals(0.0, r.quantity("ETH"), 0);
        assertEquals(T0, r.fetchedAt());
        assertEquals(7L, r.walletId());
    }
}
