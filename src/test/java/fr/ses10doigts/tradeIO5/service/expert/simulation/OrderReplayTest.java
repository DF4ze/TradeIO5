package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OrderReplay : frais, lotSz, minimum d'ordre et carry via le calcul de route du plan d'ordres")
class OrderReplayTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static OrderReplay replay(boolean paxg) {
        List<InstrumentInfo> instruments = paxg
                ? List.of(new InstrumentInfo("BTC-USDC", "BTC", "USDC", d("0.00001"), d("0.00001"), d("0.1")),
                new InstrumentInfo("PAXG-BTC", "PAXG", "BTC", d("0.001"), d("0.0001"), d("0.000001")))
                : List.of(new InstrumentInfo("BTC-USDC", "BTC", "USDC", d("0.00001"), d("0.0001"), d("0.1")));
        Map<String, OrderReplay.Quote> quotes = new java.util.HashMap<>();
        quotes.put("BTC-USDC", new OrderReplay.Quote(d("0.1"), d("0.01"), BigDecimal.ZERO));
        quotes.put("PAXG-BTC", new OrderReplay.Quote(d("0.1"), d("0.01"), BigDecimal.ZERO));
        return new OrderReplay(new OrderReplay.Schedule(instruments, Set.of("USDC"), Set.of("EUR", "USDT"), quotes, Map.of(), List.of()),
                new FeeTest());
    }

    private static OrderReplay.Request buy(String asset, double amount, double carry, double cash, Double min, Map<String, Double> usd) {
        return new OrderReplay.Request(asset, RainbowLiveAction.BUY, amount, 0, carry, cash, min, null, usd);
    }

    @Test
    @DisplayName("BTC : ask = mid + demi-spread, taille ⌊⌋ au lot, net = taille × (1 − frais) ; cash débité = mise")
    void btcBuy() {
        // mid 50000, ask 50005 ; 100/50005 = 0,0019998 -> 0,0019 ; net 0,0019 × 0,999 = 0,0018981
        OrderReplay.Executed e = replay(false).execute(buy("BTC", 100, 0, 1000, null, Map.of("BTC", 50000.0)));
        assertEquals("OK", e.outcome());
        assertEquals(-100.0, e.cashDelta(), 1e-9);
        assertEquals(0.0018981, e.qtyDelta(), 1e-12);
        assertEquals(0.0, e.carryOut());
        assertEquals("USDC>BTC", e.route());
        assertEquals(0.11, e.costPct(), 1e-9); // 0,1 frais + 0,01 demi-spread
    }

    @Test
    @DisplayName("PAXG sur Kraken : USDC>BTC>PAXG, jambe 2 dimensionnée sur le net reçu de la jambe 1")
    void paxgTwoLegs() {
        // jambe 1 : ask 50005, 100/50005 -> 0,00199 ; net 0,00198801
        // jambe 2 : mid 0,05, ask 0,050005 × 1,001 = 0,050055 ; 0,00198801/0,050055 = 0,039717 -> 0,0397 ; net 0,0396603
        OrderReplay.Executed e = replay(true).execute(buy("PAXG", 100, 0, 1000, null, Map.of("PAXG", 2500.0, "BTC", 50000.0)));
        assertEquals("OK", e.outcome());
        assertEquals("USDC>BTC>PAXG", e.route());
        assertEquals(0.0396603, e.qtyDelta(), 1e-9);
        assertEquals(-100.0, e.cashDelta(), 1e-9);
        assertEquals(0.22, e.costPct(), 1e-9);
    }

    @Test
    @DisplayName("Minimum du lien : BELOW_MIN reporte toute la mise effective ; achat passé le consomme (mise = jour + carry)")
    void belowMinCarry() {
        OrderReplay r = replay(false);
        Map<String, Double> usd = Map.of("BTC", 50000.0);
        OrderReplay.Executed d1 = r.execute(buy("BTC", 20, 0, 1000, 50.0, usd));
        assertEquals("BELOW_MIN", d1.outcome());
        assertEquals(20.0, d1.carryOut(), 1e-9);
        assertEquals(0.0, d1.cashDelta());
        OrderReplay.Executed d2 = r.execute(buy("BTC", 20, d1.carryOut(), 1000, 50.0, usd));
        assertEquals("BELOW_MIN", d2.outcome());
        assertEquals(40.0, d2.carryOut(), 1e-9);
        OrderReplay.Executed d3 = r.execute(buy("BTC", 20, d2.carryOut(), 1000, 50.0, usd));
        assertEquals("OK", d3.outcome());
        assertEquals(60.0, d3.effectiveAmount(), 1e-9);
        assertEquals(-60.0, d3.cashDelta(), 1e-9); // pas de sur-achat jusqu'au minimum
        assertEquals(0.0, d3.carryOut());
    }

    @Test
    @DisplayName("Cash insuffisant : INSUFFICIENT_FUNDS_AFTER_FEES, carry inchangé, aucun débit")
    void insufficientFunds() {
        OrderReplay.Executed e = replay(false).execute(buy("BTC", 100, 5, 10, null, Map.of("BTC", 50000.0)));
        assertEquals("INSUFFICIENT_FUNDS_AFTER_FEES", e.outcome());
        assertEquals(5.0, e.carryOut(), 1e-9);
        assertEquals(0.0, e.cashDelta());
    }

    @Test
    @DisplayName("minSz de l'exchange : montant trop petit => BELOW_MIN du plan (carry = mise effective)")
    void exchangeMin() {
        OrderReplay.Executed e = replay(false).execute(buy("BTC", 0.5, 0, 1000, null, Map.of("BTC", 50000.0)));
        assertEquals("BELOW_MIN", e.outcome());
        assertEquals(0.5, e.carryOut(), 1e-9);
    }

    @Test
    @DisplayName("Vente : bid = mid − demi-spread, net encaissé = taille × bid × (1 − frais), quantité vendue ⌊⌋ au lot")
    void sell() {
        OrderReplay.Executed e = replay(false).execute(new OrderReplay.Request("BTC", RainbowLiveAction.SELL, 1000, 0.02, 0,
                0, null, null, Map.of("BTC", 50000.0)));
        // bid 49995 ; refPrice arrondi au tick 0,1 = 49995.0 ; 0,02 × 49995 × 0,999 = 998.9
        assertEquals("OK", e.outcome());
        assertEquals(-0.02, e.qtyDelta(), 1e-12);
        assertEquals(0.02 * 49995.0 * 0.999, e.cashDelta(), 1e-6);
        assertTrue(e.cashDelta() > 0);
    }
}
