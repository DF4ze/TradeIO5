package fr.ses10doigts.tradeIO5.service.bot.indicator;

import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.result.RsiResult;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.param.RsiParams;

import java.math.BigDecimal;

public class RsiIndicator extends AIndicator<RsiParams, RsiResult>  {


    public RsiIndicator(RsiParams params) {
        super("RSI", params);

    }

    public RsiResult calculate(Wallet wallet, String asset) {
        // calcul du RSI sur wallet.getAsset()...
        return new RsiResult(BigDecimal.valueOf(32.5)); // exemple
    }
}