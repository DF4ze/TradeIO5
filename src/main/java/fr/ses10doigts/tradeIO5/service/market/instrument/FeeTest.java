package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.configuration.initializer.ExecutionControlInitializer;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Fee Test (pur) : niveau d'un coût total en %. {@code pct < warn} => GREEN ; {@code warn ≤ pct ≤ red} => WARNING (les
 * deux bornes sont dans WARNING) ; {@code pct > red} => RED. Seuils : {@code ExecutionControl} (onglet admin « Plafonds »).
 */
@Component
public class FeeTest {

    private final ExecutionControlService control;
    private final BigDecimal fixedWarnPct;
    private final BigDecimal fixedRedPct;

    /** Seuils lus en base à chaque appel (aucun cache : une modification admin agit aussitôt). */
    @Autowired
    public FeeTest(ExecutionControlService control) {
        this.control = control;
        this.fixedWarnPct = null;
        this.fixedRedPct = null;
    }

    /** Seuils fixes (tests, calculs hors base). */
    public FeeTest(BigDecimal warnPct, BigDecimal redPct) {
        if (warnPct.signum() < 0 || warnPct.compareTo(redPct) > 0) {
            throw new IllegalArgumentException("Seuils Fee Test incohérents : warn=" + warnPct + " red=" + redPct);
        }
        this.control = null;
        this.fixedWarnPct = warnPct;
        this.fixedRedPct = redPct;
    }

    /** Seuils fixes par défaut ({@link ExecutionControlInitializer}). */
    public FeeTest() {
        this(ExecutionControlInitializer.DEFAULT_FEE_TEST_WARN_PCT, ExecutionControlInitializer.DEFAULT_FEE_TEST_RED_PCT);
    }

    public FeeTestLevel level(BigDecimal totalCostPct) {
        BigDecimal warnPct = warnPct();
        if (totalCostPct.compareTo(warnPct) < 0) {
            return FeeTestLevel.GREEN;
        }
        return totalCostPct.compareTo(redPct()) <= 0 ? FeeTestLevel.WARNING : FeeTestLevel.RED;
    }

    public BigDecimal warnPct() {
        return control == null ? fixedWarnPct : ExecutionControlService.feeWarnPct(control.current());
    }

    public BigDecimal redPct() {
        return control == null ? fixedRedPct : ExecutionControlService.feeRedPct(control.current());
    }
}
