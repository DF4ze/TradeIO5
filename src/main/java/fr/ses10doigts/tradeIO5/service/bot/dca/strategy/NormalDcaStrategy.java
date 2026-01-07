package fr.ses10doigts.tradeIO5.service.bot.dca.strategy;

import fr.ses10doigts.tradeIO5.model.dto.bot.DcaDecision;
import fr.ses10doigts.tradeIO5.model.dto.bot.DcaSettings;
import org.springframework.stereotype.Service;

@Service
public class NormalDcaStrategy implements DcaStrategy{
    @Override
    public String getCodeName() {
        return "NORMAL";
    }

    @Override
    public DcaDecision decide(DcaSettings settings) {
        return null;
    }
}
