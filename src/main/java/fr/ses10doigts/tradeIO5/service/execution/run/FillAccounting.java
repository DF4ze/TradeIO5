package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.service.execution.exchange.Fill;
import fr.ses10doigts.tradeIO5.service.execution.exchange.OrderState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Comptabilité d'une étape à partir de l'état de l'ordre et de ses remplissages : statut, quantité remplie, prix moyen,
 * frais réels, quantité nette reçue (frais déduits quand ils sont prélevés dans l'actif reçu) et slippage réel. Logique
 * pure, sans accès à l'exchange ni à la base.
 */
public final class FillAccounting {

    private static final int SCALE = 18;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Résultat comptable ; {@code receivedCurrency} = base à l'achat, cotation à la vente. */
    public record Outcome(StepStatus status, BigDecimal filledSz, BigDecimal avgPx, BigDecimal fee, String feeCurrency,
                          BigDecimal received, String receivedCurrency) {
    }

    private FillAccounting() {
    }

    /**
     * @param sentSz taille envoyée ; remplie entièrement => FILLED, en partie => PARTIAL, rien => CANCELED
     * @param fills remplissages (peut être vide : on retombe sur l'état agrégé de l'ordre)
     */
    public static Outcome of(StepSide side, InstrumentInfo instrument, BigDecimal sentSz, OrderState state, List<Fill> fills) {
        BigDecimal filled = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        String feeCcy = null;
        for (Fill f : fills) {
            filled = filled.add(f.sz());
            notional = notional.add(f.sz().multiply(f.px()));
            fee = fee.add(f.fee());
            if (feeCcy == null && f.fee().signum() != 0) {
                feeCcy = f.feeCcy();
            }
        }
        BigDecimal avg;
        if (filled.signum() > 0) {
            avg = notional.divide(filled, SCALE, RoundingMode.HALF_UP);
        } else {
            filled = state.accFillSz() == null ? BigDecimal.ZERO : state.accFillSz();
            avg = state.avgPx();
            notional = avg == null ? BigDecimal.ZERO : filled.multiply(avg);
        }
        StepStatus status = filled.signum() <= 0 ? StepStatus.CANCELED
                : filled.compareTo(sentSz) >= 0 ? StepStatus.FILLED : StepStatus.PARTIAL;

        String receivedCcy = side == StepSide.BUY ? instrument.base() : instrument.quote();
        BigDecimal received = side == StepSide.BUY ? filled : notional;
        if (feeCcy != null && feeCcy.equalsIgnoreCase(receivedCcy)) {
            received = received.subtract(fee);
        }
        return new Outcome(status, filled, avg, fee, feeCcy, received.max(BigDecimal.ZERO), receivedCcy);
    }

    /** Slippage réel (%) : prix moyen de remplissage contre le mid du re-devis (positif = coût). Nul sans prix ou sans mid. */
    public static BigDecimal realSlippagePct(StepSide side, BigDecimal avgPx, BigDecimal mid) {
        if (avgPx == null || mid == null || mid.signum() <= 0) {
            return null;
        }
        BigDecimal delta = side == StepSide.BUY ? avgPx.subtract(mid) : mid.subtract(avgPx);
        return delta.multiply(HUNDRED).divide(mid, 6, RoundingMode.HALF_UP);
    }
}
