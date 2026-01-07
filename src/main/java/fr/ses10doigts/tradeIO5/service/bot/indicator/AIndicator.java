package fr.ses10doigts.tradeIO5.service.bot.indicator;

import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.param.IndicatorParams;
import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.result.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import lombok.Data;

@Data
public abstract class AIndicator<T extends IndicatorParams, R extends IndicatorResult> {

    String codeName;
    T params;

    protected AIndicator( String code, T params ){
        codeName = code;
        this.params = params;
    }

    public abstract R calculate(Wallet wallet, String asset);
}
