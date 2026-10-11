package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CashLedger : pool de cash, réservation tout-ou-rien")
class CashLedgerTest {

    @Test
    @DisplayName("Achats successifs : chacun voit le reste ; un achat > reste est refusé en entier")
    void reservesAllOrNothing() {
        CashLedger ledger = new CashLedger(100.0);

        assertTrue(ledger.tryReserve(60.0));
        assertEquals(40.0, ledger.remaining(), 1e-12);
        assertFalse(ledger.tryReserve(50.0));
        assertEquals(60.0, ledger.reserved(), 1e-12);
        assertTrue(ledger.tryReserve(40.0));
        assertEquals(0.0, ledger.remaining(), 1e-12);
    }

    @Test
    @DisplayName("Achat égal au cash restant accepté (tolérance flottante)")
    void exactRemainingAccepted() {
        CashLedger ledger = new CashLedger(0.3);

        assertTrue(ledger.tryReserve(0.1 + 0.2));
    }

    @Test
    @DisplayName("forceReserve (rejeu) : réserve sans contrôle, le reste ne devient jamais négatif")
    void forceReserve() {
        CashLedger ledger = new CashLedger(10.0);

        ledger.forceReserve(25.0);

        assertEquals(25.0, ledger.reserved(), 1e-12);
        assertEquals(0.0, ledger.remaining(), 1e-12);
    }
}
