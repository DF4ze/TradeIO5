package fr.ses10doigts.tradeIO5.model.dto.bot.indicator.param;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
public class RsiParams extends IndicatorParams{
    private int period;
    private double overbought;
    private double oversold;

    public RsiParams(int period, double overbought, double oversold) {
        this.period = period;
        this.overbought = overbought;
        this.oversold = oversold;
    }
}
