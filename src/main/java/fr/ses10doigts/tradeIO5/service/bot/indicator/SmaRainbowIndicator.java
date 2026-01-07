package fr.ses10doigts.tradeIO5.service.bot.indicator;

import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.param.SmaRainbowParams;
import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.result.SmaRainbowResult;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;

public class SmaRainbowIndicator extends AIndicator<SmaRainbowParams, SmaRainbowResult>{

    public SmaRainbowIndicator(SmaRainbowParams params) {
        super("SMA_RAINBOW", params);
    }


    @Override
    public SmaRainbowResult calculate(Wallet wallet, String asset) {
        return null;
    }
}
