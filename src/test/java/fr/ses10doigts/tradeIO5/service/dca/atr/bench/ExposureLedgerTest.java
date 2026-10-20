package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExposureLedger / ExposureBook")
class ExposureLedgerTest {

    private static PortfolioReading reading(double cash, Map<String, Double> positions) {
        return new PortfolioReading(cash, positions, Instant.EPOCH, PortfolioStatus.OK, 1L);
    }

    @Test
    @DisplayName("Exposition = valeur des actifs ÷ (actifs + cash), multi-actifs")
    void exposure() {
        ExposureLedger ledger = ExposureLedger.of(reading(500, Map.of("BTC", 1.0, "ETH", 10.0)),
                Map.of("BTC", 100.0, "ETH", 40.0));
        assertTrue(ledger.isKnown());
        assertEquals(500.0, ledger.assetsValue(), 1e-9);
        assertEquals(1000.0, ledger.totalValue(), 1e-9);
        assertEquals(50.0, ledger.exposurePct(), 1e-9);
    }

    @Test
    @DisplayName("Un achat augmente P, une vente la diminue (V constant), jamais sous 0")
    void updates() {
        ExposureLedger ledger = ExposureLedger.of(reading(500, Map.of("BTC", 5.0)), Map.of("BTC", 100.0));
        ledger.applyBuy(100);
        assertEquals(60.0, ledger.exposurePct(), 1e-9);
        ledger.applySell(700);
        assertEquals(0.0, ledger.assetsValue(), 1e-9);
    }

    @Test
    @DisplayName("Prix manquant pour un actif détenu ou V = 0 => exposition inconnue, jamais 0 silencieux")
    void unknown() {
        assertFalse(ExposureLedger.of(reading(500, Map.of("BTC", 1.0, "ETH", 2.0)), Map.of("BTC", 100.0)).isKnown());
        assertFalse(ExposureLedger.of(reading(0, Map.of()), Map.of()).isKnown());
        ExposureLedger unknown = ExposureLedger.unknown();
        unknown.applyBuy(100);
        assertEquals(0.0, unknown.assetsValue(), 1e-9);
    }

    @Test
    @DisplayName("Un actif à quantité nulle sans prix n'empêche pas le calcul")
    void zeroQuantityNeedsNoPrice() {
        assertTrue(ExposureLedger.of(reading(500, Map.of("BTC", 1.0, "ETH", 0.0)), Map.of("BTC", 100.0)).isKnown());
    }

    @Test
    @DisplayName("Book : un seul ledger par wallet, lecture non OK sans ledger => null")
    void bookCreatesOncePerWallet() {
        ExposureBook book = new ExposureBook();
        Map<String, Double> closes = Map.of("BTC", 100.0);
        ExposureLedger first = book.ledgerFor(1L, reading(500, Map.of("BTC", 5.0)), closes);
        assertSame(first, book.ledgerFor(1L, reading(999, Map.of("BTC", 9.0)), closes));
        assertNull(book.ledgerFor(2L, PortfolioReading.unavailable(2L, Instant.EPOCH), closes));
    }

    @Test
    @DisplayName("Rejeu : l'action d'origine met à jour le ledger existant, ou est appliquée à sa création")
    void replay() {
        Map<String, Double> closes = Map.of("BTC", 100.0);
        ExposureBook existing = new ExposureBook();
        ExposureLedger ledger = existing.ledgerFor(1L, reading(500, Map.of("BTC", 5.0)), closes);
        existing.replay(1L, RainbowLiveAction.BUY, 100);
        assertEquals(600.0, ledger.assetsValue(), 1e-9);

        ExposureBook late = new ExposureBook();
        late.replay(1L, RainbowLiveAction.SELL, 200);
        late.replay(1L, RainbowLiveAction.NONE, 999);
        assertEquals(300.0, late.ledgerFor(1L, reading(500, Map.of("BTC", 5.0)), closes).assetsValue(), 1e-9);
    }
}
