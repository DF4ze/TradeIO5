package fr.ses10doigts.tradeIO5.service.bot.dca.strategy;

import fr.ses10doigts.tradeIO5.model.dto.bot.DcaDecision;
import fr.ses10doigts.tradeIO5.model.dto.bot.DcaSettings;

public interface DcaStrategy {

    String getCodeName();

    /**
     * Retourne la décision à appliquer pour ce DCA
     */
    DcaDecision decide(DcaSettings settings);


}