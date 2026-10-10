package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.service.execution.exchange.Fill;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderPhase;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("FillAccounting : statut, prix moyen, frais, quantité nette reçue, slippage réel")
class FillAccountingTest {

    private static final InstrumentInfo BTC_USDC = new InstrumentInfo("BTC-USDC", "BTC", "USDC", new BigDecimal("0.00001"),
            new BigDecimal("0.00000001"), new BigDecimal("0.1"));

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Fill fill(StepSide side, String px, String sz, String fee, String ccy) {
        return new Fill("t", "o", "c", "BTC-USDC", side, d(px), d(sz), d(fee), ccy, Instant.EPOCH);
    }

    private static OrderState state(OrderPhase phase, String filled) {
        return new OrderState("c", "o", phase, d(filled), null);
    }

    @Test
    @DisplayName("Achat rempli : frais prélevés en base => quantité nette = remplie - frais")
    void buyNetOfFee() {
        var o = FillAccounting.of(StepSide.BUY, BTC_USDC, d("0.01"), state(OrderPhase.FILLED, "0.01"),
                List.of(fill(StepSide.BUY, "100000", "0.01", "0.00001", "BTC")));
        assertEquals(StepStatus.FILLED, o.status());
        assertEquals(0, d("0.00999").compareTo(o.received()));
        assertEquals("BTC", o.receivedCurrency());
        assertEquals(0, d("100000").compareTo(o.avgPx()));
    }

    @Test
    @DisplayName("Vente : reçu en cotation, frais en cotation déduits")
    void sellNetOfFee() {
        var o = FillAccounting.of(StepSide.SELL, BTC_USDC, d("0.01"), state(OrderPhase.FILLED, "0.01"),
                List.of(fill(StepSide.SELL, "100000", "0.01", "1", "USDC")));
        assertEquals(0, d("999").compareTo(o.received()));
        assertEquals("USDC", o.receivedCurrency());
    }

    @Test
    @DisplayName("Partiel => PARTIAL ; rien rempli => CANCELED ; prix moyen pondéré")
    void partialAndNone() {
        var partial = FillAccounting.of(StepSide.BUY, BTC_USDC, d("0.01"), state(OrderPhase.CANCELED, "0.006"), List.of(
                fill(StepSide.BUY, "100", "0.002", "0", "BTC"), fill(StepSide.BUY, "200", "0.004", "0", "BTC")));
        assertEquals(StepStatus.PARTIAL, partial.status());
        assertEquals(0, d("166.666666666666666667").compareTo(partial.avgPx()));
        var none = FillAccounting.of(StepSide.BUY, BTC_USDC, d("0.01"), state(OrderPhase.CANCELED, "0"), List.of());
        assertEquals(StepStatus.CANCELED, none.status());
    }

    @Test
    @DisplayName("Slippage réel : achat plus cher que le mid = coût positif ; sans mid => nul")
    void slippage() {
        assertEquals(0, d("0.5").compareTo(FillAccounting.realSlippagePct(StepSide.BUY, d("100.5"), d("100"))));
        assertEquals(0, d("0.5").compareTo(FillAccounting.realSlippagePct(StepSide.SELL, d("99.5"), d("100"))));
        assertNull(FillAccounting.realSlippagePct(StepSide.BUY, d("100"), null));
    }
}
