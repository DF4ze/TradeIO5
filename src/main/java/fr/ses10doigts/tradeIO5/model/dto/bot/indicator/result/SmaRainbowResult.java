package fr.ses10doigts.tradeIO5.model.dto.bot.indicator.result;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = true)
@Data
@Builder
public class SmaRainbowResult extends IndicatorResult{
    private BigDecimal priceBase;
    private BigDecimal priceUnder1;
    private BigDecimal priceUnder2;
    private BigDecimal priceUpper1;
    private BigDecimal priceUpper2;
    private BigDecimal priceUpper3;
}
