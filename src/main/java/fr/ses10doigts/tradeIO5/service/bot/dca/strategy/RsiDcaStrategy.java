package fr.ses10doigts.tradeIO5.service.bot.dca.strategy;

import fr.ses10doigts.tradeIO5.model.dto.bot.DcaDecision;
import fr.ses10doigts.tradeIO5.model.dto.bot.DcaSettings;
import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.param.RsiParams;
import fr.ses10doigts.tradeIO5.model.dto.bot.indicator.result.RsiResult;
import fr.ses10doigts.tradeIO5.model.enumerate.bot.Action;
import fr.ses10doigts.tradeIO5.model.enumerate.bot.QuantityMode;
import fr.ses10doigts.tradeIO5.service.bot.indicator.RsiIndicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class RsiDcaStrategy implements DcaStrategy {

    private final BigDecimal oversoldThreshold = BigDecimal.valueOf(30);
    private final BigDecimal overboughtThreshold = BigDecimal.valueOf(70);

    @Override
    public String getCodeName() {
        return "RSI";
    }

    @Override
    public DcaDecision decide(DcaSettings settings) {
        RsiIndicator rsiIndic = new RsiIndicator(new RsiParams(12, 70, 30));

        RsiResult rsi = rsiIndic.calculate(settings.getWallet(), settings.getAsset());

        DcaDecision decision = new DcaDecision();

        if (rsi.getValue().compareTo(oversoldThreshold) < 0) {
            decision.setAction(Action.BUY);
            decision.setQuantityMode(QuantityMode.UNIT);
            decision.setQuantity(BigDecimal.valueOf(25)); // ex. 25 USDC

        } else if (rsi.getValue().compareTo(overboughtThreshold) > 0) {
            decision.setAction(Action.SELL);
            decision.setQuantityMode(QuantityMode.PERCENT);
            decision.setQuantity(BigDecimal.valueOf(10)); // 10% du wallet
        } else {
            decision.setAction(Action.NONE);
        }

        return decision;
    }

}
