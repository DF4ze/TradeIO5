package fr.ses10doigts.tradeIO5.model.dto.bot.indicator.param;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SmaRainbowParams extends IndicatorParams{

    private int period;
    private double percentUnder1;
    private double percentUnder2;
    private double percentUpper1;
    private double percentUpper2;
    private double percentUpper3;
}
