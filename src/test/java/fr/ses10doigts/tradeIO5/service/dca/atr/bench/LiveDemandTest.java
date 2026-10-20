package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LiveDemandTest {

    @Test
    @DisplayName("Montant de base du lien : l'achat est proportionnel, la vente inchangée")
    void scalesBuyOnly() {
        LiveDemand base = LiveDemand.fixed(20, 3, 100, 10);
        LiveDemand scaled = base.withLinkBaseAmount(25.0);
        assertEquals(50.0, scaled.buyAmount(), 1e-9);
        assertEquals(3.0, scaled.sellQty(1), 1e-9);
        assertEquals(25.0, scaled.baseAmount(), 1e-9);
    }

    @Test
    @DisplayName("Montant de base du lien nul/invalide ou base inconnue : demande inchangée")
    void unchanged() {
        LiveDemand base = LiveDemand.fixed(20, 0, 100, 10);
        assertSame(base, base.withLinkBaseAmount(null));
        assertSame(base, base.withLinkBaseAmount(0.0));
        LiveDemand unknownBase = LiveDemand.fixed(20, 0, 100, 0);
        assertSame(unknownBase, unknownBase.withLinkBaseAmount(25.0));
    }
}
