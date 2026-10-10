package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Fee Test (pur) : niveau d'un coût total en %. {@code pct < warn} => GREEN ; {@code warn ≤ pct ≤ red} => WARNING (les
 * deux bornes sont dans WARNING) ; {@code pct > red} => RED. Seuils : {@link ExecutionDefaults#FEE_TEST_WARN_PROPERTY}
 * et {@link ExecutionDefaults#FEE_TEST_RED_PROPERTY}.
 */
@Component
public class FeeTest {

    private final BigDecimal warnPct;
    private final BigDecimal redPct;

    @Autowired
    public FeeTest(
            @Value("${" + ExecutionDefaults.FEE_TEST_WARN_PROPERTY + ":0.2}") BigDecimal warnPct,
            @Value("${" + ExecutionDefaults.FEE_TEST_RED_PROPERTY + ":0.8}") BigDecimal redPct) {
        if (warnPct.signum() < 0 || warnPct.compareTo(redPct) > 0) {
            throw new IllegalArgumentException("Seuils Fee Test incohérents : warn=" + warnPct + " red=" + redPct);
        }
        this.warnPct = warnPct;
        this.redPct = redPct;
    }

    public FeeTest() {
        this(ExecutionDefaults.FEE_TEST_WARN_PCT, ExecutionDefaults.FEE_TEST_RED_PCT);
    }

    public FeeTestLevel level(BigDecimal totalCostPct) {
        if (totalCostPct.compareTo(warnPct) < 0) {
            return FeeTestLevel.GREEN;
        }
        return totalCostPct.compareTo(redPct) <= 0 ? FeeTestLevel.WARNING : FeeTestLevel.RED;
    }

    public BigDecimal warnPct() {
        return warnPct;
    }

    public BigDecimal redPct() {
        return redPct;
    }
}
