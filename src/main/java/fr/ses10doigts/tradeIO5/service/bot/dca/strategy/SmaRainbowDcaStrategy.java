package fr.ses10doigts.tradeIO5.service.bot.dca.strategy;

import fr.ses10doigts.tradeIO5.model.dto.bot.DcaDecision;
import fr.ses10doigts.tradeIO5.model.dto.bot.DcaSettings;
import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.param.SmaRainbowParams;
import fr.ses10doigts.tradeIO5.service.bot.indicator.SmaRainbowIndicator;
import org.springframework.stereotype.Service;

@Service
public class SmaRainbowDcaStrategy implements DcaStrategy{
    @Override
    public String getCodeName() {
        return "SMA_RAINBOW";
    }

    @Override
    public DcaDecision decide(DcaSettings settings) {

 /*       settings.getStrategyParams()

        SmaRainbowParams.builder()
                .period(12)
                .percentUnder1()

        SmaRainbowParams smaRainbowParams = new SmaRainbowParams();
        smaRainbowParams.setPeriod(21);

        new SmaRainbowIndicator();
*/

        return null;
    }
}
