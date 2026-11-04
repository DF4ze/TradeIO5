package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.service.portfolio.PnlLedger.Fill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PnlLedger : rejeu en prix moyen pondéré, réalisé, frais")
class PnlLedgerTest {

    private static final LocalDateTime D0 = LocalDateTime.of(2026, 10, 1, 12, 0);

    private static Fill buy(int day, double qty, double price) {
        return new Fill(D0.plusDays(day), true, qty, price, 0, 0);
    }

    private static Fill sell(int day, double qty, double price) {
        return new Fill(D0.plusDays(day), false, qty, price, 0, 0);
    }

    @Test
    @DisplayName("Vente : gain = qté x (prix − PRU) ; le PRU de ce qui reste ne bouge pas")
    void sellRealizesAgainstAverage() {
        var r = PnlLedger.replay(List.of(buy(0, 1, 100), buy(1, 1, 200), sell(2, 0.5, 300)));
        assertEquals(75.0, r.realized(), 1e-9);          // 0,5 x (300 − 150)
        assertEquals(75.0, r.soldCost(), 1e-9);
        assertEquals(1.5, r.quantity(), 1e-9);
        assertEquals(150.0, r.avgPrice(), 1e-9);
        assertEquals(1, r.sells());
        assertFalse(r.incomplete());
    }

    @Test
    @DisplayName("Un achat après vente re-pondère sur la quantité restante")
    void buyAfterSellReweights() {
        var r = PnlLedger.replay(List.of(buy(0, 1, 100), sell(1, 0.5, 200), buy(2, 0.5, 300)));
        assertEquals(50.0, r.realized(), 1e-9);
        assertEquals(200.0, r.avgPrice(), 1e-9);         // (0,5 x 100 + 0,5 x 300) / 1
    }

    @Test
    @DisplayName("Frais : ajoutés au coût à l'achat, déduits du produit à la vente")
    void feesInStable() {
        var r = PnlLedger.replay(List.of(new Fill(D0, true, 1, 100, 0, 2), new Fill(D0.plusDays(1), false, 1, 150, 0, 3)));
        assertEquals(150.0 - 3.0 - 102.0, r.realized(), 1e-9);
        assertEquals(5.0, r.fees(), 1e-9);
        assertEquals(250.0, r.volume(), 1e-9);           // 100 acheté + 150 vendu
        assertEquals(100.0, r.bought(), 1e-9);
    }

    @Test
    @DisplayName("Frais prélevés dans l'actif acheté : quantité nette réduite, PRU plus élevé")
    void feesInBaseAsset() {
        var r = PnlLedger.replay(List.of(new Fill(D0, true, 1, 100, 0.01, 0)));
        assertEquals(0.99, r.quantity(), 1e-9);
        assertEquals(100.0 / 0.99, r.avgPrice(), 1e-9);
        assertEquals(1.0, r.fees(), 1e-9);               // 0,01 x 100 $
    }

    @Test
    @DisplayName("Vente supérieure à la quantité connue : plafonnée, historique incomplet")
    void oversellIsCapped() {
        var r = PnlLedger.replay(List.of(buy(0, 1, 100), sell(1, 3, 200)));
        assertEquals(100.0, r.realized(), 1e-9);         // 1 x (200 − 100)
        assertTrue(r.incomplete());
        assertEquals(0.0, r.quantity(), 1e-9);
        assertNull(r.avgPrice());
    }

    @Test
    @DisplayName("Vente sans aucun achat : rien de réalisé, incomplet")
    void sellWithoutBuy() {
        var r = PnlLedger.replay(List.of(sell(0, 1, 100)));
        assertEquals(0.0, r.realized(), 1e-9);
        assertEquals(0, r.sells());
        assertTrue(r.incomplete());
    }

    @Test
    @DisplayName("Ordre d'entrée quelconque : rejeu chronologique")
    void sortedByTime() {
        var r = PnlLedger.replay(List.of(sell(2, 1, 200), buy(0, 1, 100)));
        assertEquals(100.0, r.realized(), 1e-9);
    }
}
