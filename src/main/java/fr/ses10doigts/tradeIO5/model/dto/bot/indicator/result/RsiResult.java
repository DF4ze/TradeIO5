package fr.ses10doigts.tradeIO5.model.dto.bot.indicator.result;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = true)
@Data
public class RsiResult extends IndicatorResult{
    private BigDecimal value;

    public RsiResult( BigDecimal result ){
        value = result;
    }
}
