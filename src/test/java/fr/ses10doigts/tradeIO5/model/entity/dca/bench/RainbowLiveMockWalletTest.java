package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RainbowLiveMockWallet")
class RainbowLiveMockWalletTest {

    private static RainbowLiveMockWallet wallet(double cash) {
        return RainbowLiveMockWallet.builder().assetSymbol("BTC").cashUsd(cash).updatedAt(Instant.EPOCH).build();
    }

    @Test
    @DisplayName("PortfolioView : cash USDC, quantité par actif (0 pour un autre actif)")
    void portfolioView() {
        RainbowLiveMockWallet w = wallet(1000);
        w.applyBuy(100, 0.002);

        assertEquals(900, w.cash(), 1e-9);
        assertEquals(0.002, w.quantity("BTC"), 1e-12);
        assertEquals(0.0, w.quantity("ETH"), 0.0);
    }

    @Test
    @DisplayName("Achat puis vente mettent à jour cash et position")
    void buyThenSell() {
        RainbowLiveMockWallet w = wallet(1000);
        w.applyBuy(250, 0.005);
        w.applySell(120, 0.002);

        assertEquals(870, w.getCashUsd(), 1e-9);
        assertEquals(0.003, w.getPositionQuantity(), 1e-12);
    }

    @Test
    @DisplayName("Plus de cash ⇒ plus d'achat")
    void buyRefusedWithoutCash() {
        RainbowLiveMockWallet w = wallet(50);

        assertThrows(IllegalStateException.class, () -> w.applyBuy(50.01, 1));
        assertEquals(50, w.getCashUsd(), 0.0);
        assertEquals(0.0, w.getPositionQuantity(), 0.0);
    }

    @Test
    @DisplayName("Vente refusée au-delà de la position ; montants non positifs refusés")
    void invalidOperations() {
        RainbowLiveMockWallet w = wallet(100);

        assertThrows(IllegalStateException.class, () -> w.applySell(10, 1));
        assertThrows(IllegalArgumentException.class, () -> w.applyBuy(0, 1));
        assertThrows(IllegalArgumentException.class, () -> w.applySell(10, 0));
    }
}
