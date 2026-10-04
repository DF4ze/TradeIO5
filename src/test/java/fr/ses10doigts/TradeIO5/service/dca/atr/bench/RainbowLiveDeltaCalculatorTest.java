package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDeltaCalculator.Delta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RainbowLiveDeltaCalculator")
class RainbowLiveDeltaCalculatorTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);

    private static RainbowLivePassBlock block(double close, int zone, RainbowLiveAction type, Double amount, Double qty,
                                              boolean buyArmed) {
        return RainbowLivePassBlock.builder().close(close).sma(100.0).atr(2.0).zone(zone).buyArmed(buyArmed)
                .actionType(type).actionAmountUsdc(amount).actionQuantity(qty).build();
    }

    private static RainbowLiveRun run(int off, RainbowLivePassBlock a, RainbowLivePassBlock b) {
        return RainbowLiveRun.builder().day(D1.plusDays(off)).pass2355(a).pass0005(b).build();
    }

    @Test
    @DisplayName("Jours à 2 blocs comparés, jours sans 00:05 ignorés ; écarts, action/zone/états divergents")
    void delta() {
        List<RainbowLiveRun> runs = List.of(
                run(0, block(100, 2, RainbowLiveAction.BUY, 10.0, 0.1, true), block(101, 3, RainbowLiveAction.NONE, null, null, false)),
                run(1, block(110, 2, RainbowLiveAction.NONE, null, null, true), null),
                run(2, block(120, 2, RainbowLiveAction.NONE, null, null, true), block(120, 2, RainbowLiveAction.NONE, null, null, true)),
                run(3, null, block(130, 2, RainbowLiveAction.NONE, null, null, true)));

        Delta d = RainbowLiveDeltaCalculator.compute(runs);

        assertEquals(2, d.daysCompared());
        assertEquals(2, d.daysIgnored());
        assertEquals(1, d.daysActionDiffers());
        assertEquals(D1, d.actionDifferences().getFirst().day());
        assertEquals(RainbowLiveAction.BUY, d.actionDifferences().getFirst().pass2355().type());
        assertEquals(RainbowLiveAction.NONE, d.actionDifferences().getFirst().pass0005().type());
        assertEquals(0.5, d.indicators().get("close").meanAbs(), 1e-9);
        assertEquals(1.0, d.indicators().get("close").maxAbs(), 1e-9);
        assertEquals(1.0, d.indicators().get("close").maxPct(), 1e-9);
        assertEquals(0.0, d.indicators().get("sma").maxAbs(), 0.0);
        assertEquals(1, d.zoneDivergences().size());
        assertEquals(3, d.zoneDivergences().getFirst().zone0005());
        assertEquals(List.of("buyArmed"), d.stateDivergences().getFirst().fields());
    }

    @Test
    @DisplayName("Aucun jour comparable : tout à 0")
    void empty() {
        Delta d = RainbowLiveDeltaCalculator.compute(List.of());
        assertEquals(0, d.daysCompared());
        assertTrue(d.actionDifferences().isEmpty());
        assertEquals(0, d.indicators().get("close").meanAbs(), 0.0);
    }
}
